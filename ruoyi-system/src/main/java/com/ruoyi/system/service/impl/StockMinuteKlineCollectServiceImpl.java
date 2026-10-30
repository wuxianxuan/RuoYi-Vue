package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockRisingState;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockRisingStateMapper;
import com.ruoyi.system.service.IStockMinuteKlineCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 分时K线增量落库实现
 *
 * 候选范围（取并集）：
 *  1. 最近 days 个交易日涨停过的股票 —— 服务首板/二板回调策略；
 *  2. stock_rising_state 里的全部池内股票 —— 服务趋势上升策略的后续盘口分析。
 *
 * 增量策略：先查每只票库中已有的最大分时时间，只写更晚的 bar。
 * 首次运行会补齐腾讯接口能给的最近 ~320 根（约 7 个交易日），之后每天只写当天新增。
 *
 * 存储复用 stock_kline（kline_type=m5），唯一键 (code,type,time) 天然幂等。
 *
 * @author ruoyi
 */
@Service
public class StockMinuteKlineCollectServiceImpl implements IStockMinuteKlineCollectService
{
    private static final Logger log = LoggerFactory.getLogger(StockMinuteKlineCollectServiceImpl.class);

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockRisingStateMapper stockRisingStateMapper;

    /** 分时K线类型 */
    @Value("${stock.minute-collect.minute-type:m5}")
    private String minuteType;

    /** 是否启用分时落库 */
    @Value("${stock.minute-collect.enabled:true}")
    private boolean enabled;

    /** 是否纳入趋势上涨池 */
    @Value("${stock.minute-collect.include-pool:true}")
    private boolean includePool;

    /** 并行线程数 */
    @Value("${stock.minute-collect.thread-pool-size:8}")
    private int threadPoolSize;

    @Override
    public void syncMinuteKline(int days)
    {
        if (!enabled)
        {
            log.info("分时K线落库已关闭（minute-collect.enabled=false），跳过");
            return;
        }
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(Math.max(days, 1) + 1L);
        log.info("========== 分时K线增量同步开始：{} ~ {} ==========", start, end);

        Set<String> codes = buildCodes(end, days);
        if (codes.isEmpty())
        {
            log.info("无候选股票，跳过分时同步");
            return;
        }
        log.info("分时候选股票 {} 只（涨停股 + 趋势池）", codes.size());

        List<String> codeList = new ArrayList<>(codes);
        Map<String, Date> maxMap = loadMaxMinuteTime(codeList);

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger empty = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<?>> futures = new ArrayList<>(codeList.size());
        try
        {
            for (String code : codeList)
            {
                futures.add(pool.submit(() -> collectOne(code, start, maxMap.get(code), ok, empty, fail, rows)));
            }
        }
        finally
        {
            pool.shutdown();
        }
        for (Future<?> f : futures)
        {
            try
            {
                f.get(60, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                log.debug("分时同步任务异常: {}", e.getMessage());
            }
        }
        log.info("分时K线同步完成：成功 {} 只，无数据 {} 只，失败 {} 只，新增 {} 行", ok.get(), empty.get(), fail.get(), rows.get());
    }

    /** 候选股票：最近 days 个交易日涨停股 + 趋势池 */
    private Set<String> buildCodes(LocalDate end, int days)
    {
        Set<String> codes = new LinkedHashSet<>();
        String cursor = end.toString();
        for (int i = 0; i <= days; i++)
        {
            List<StockLimitUpRecord> records = stockLimitUpRecordMapper.selectByTradeDate(cursor);
            if (records != null)
            {
                for (StockLimitUpRecord r : records)
                {
                    String code = r.getSecuCode();
                    if (code == null || code.isEmpty())
                    {
                        continue;
                    }
                    if (r.getSecuName() != null && r.getSecuName().toUpperCase().contains("ST"))
                    {
                        continue;
                    }
                    codes.add(code);
                }
            }
            String prev = stockKlineMapper.selectMaxTradeDateBefore(cursor);
            if (prev == null)
            {
                break;
            }
            cursor = prev;
        }
        if (includePool)
        {
            List<StockRisingState> states = stockRisingStateMapper.selectAll();
            if (states != null)
            {
                for (StockRisingState st : states)
                {
                    if (st.getStockCode() != null)
                    {
                        codes.add(st.getStockCode());
                    }
                }
            }
        }
        return codes;
    }

    /** 每只票库中已有的最大分时时间 */
    private Map<String, Date> loadMaxMinuteTime(List<String> codes)
    {
        Map<String, Date> map = new java.util.HashMap<>();
        if (codes.isEmpty())
        {
            return map;
        }
        try
        {
            List<Map<String, Object>> rows = stockKlineMapper.selectMaxMinuteTime(codes, minuteType);
            if (rows != null)
            {
                for (Map<String, Object> row : rows)
                {
                    Object code = row.get("stockCode");
                    Object maxTime = row.get("maxTime");
                    if (code != null && maxTime instanceof Date)
                    {
                        map.put(code.toString(), (Date) maxTime);
                    }
                }
            }
        }
        catch (Exception e)
        {
            log.warn("查询已有分时最大时间失败，本次全量覆盖: {}", e.getMessage());
        }
        return map;
    }

    private void collectOne(String code, LocalDate start, Date maxTime,
                            AtomicInteger ok, AtomicInteger empty, AtomicInteger fail, AtomicInteger rows)
    {
        try
        {
            TencentKlineDataSource.MinuteSnapshot snap = tencentKlineDataSource.fetchMinuteSnapshot(
                    code, inferMarket(code), minuteType, null);
            List<StockKline> bars = snap.getBars();
            if (bars == null || bars.isEmpty())
            {
                empty.incrementAndGet();
                return;
            }
            List<StockKline> list = bars.stream()
                    .filter(b -> b.getTradeTime() != null && b.getClosePrice() != null)
                    .filter(b -> !toLocalDate(b.getTradeTime()).isBefore(start))
                    .filter(b -> maxTime == null || b.getTradeTime().after(maxTime))
                    .peek(b -> b.setKlineType(minuteType))
                    .collect(Collectors.toList());
            if (list.isEmpty())
            {
                empty.incrementAndGet();
                return;
            }
            stockKlineMapper.upsertKlineBatch(list);
            ok.incrementAndGet();
            rows.addAndGet(list.size());
        }
        catch (Exception e)
        {
            fail.incrementAndGet();
            log.debug("分时同步失败 code={}: {}", code, e.getMessage());
        }
    }

    private LocalDate toLocalDate(Date date)
    {
        return java.time.Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private String inferMarket(String code)
    {
        return code.startsWith("6") ? "SH" : "SZ";
    }
}
