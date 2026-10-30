package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.service.IStockMinuteKlineCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 涨停股分时K线落库实现
 *
 * 落库范围：目标交易日 X 的当天分时，股票 = 最近 daysBack 个交易日内涨停过的股票，
 * 即每只涨停股都会在「涨停日 D、D+1、D+2、D+3」各被采集一次，自动拼齐 4 天分时。
 *
 * 存储：复用 stock_kline 表，kline_type = m5（char(2) 足够），唯一键 (code,type,time) 天然幂等。
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

    /** 分时K线类型 */
    @Value("${stock.minute-collect.minute-type:m5}")
    private String minuteType;

    /** 往前覆盖几个交易日的涨停股（默认3：加上当天正好覆盖 D~D+3） */
    @Value("${stock.minute-collect.days-back:3}")
    private int daysBack;

    /** 并行线程数 */
    @Value("${stock.minute-collect.thread-pool-size:8}")
    private int threadPoolSize;

    @Override
    public void execute(String date)
    {
        LocalDate day = parseDate(date);
        log.info("========== 涨停股分时K线落库开始：{} ==========", day);

        // 1. 收集最近 daysBack 个交易日涨停过的股票（含当天）
        Set<String> codes = new LinkedHashSet<>();
        String cursor = day.toString();
        for (int i = 0; i <= daysBack; i++)
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
            if (i < daysBack)
            {
                String prev = stockKlineMapper.selectMaxTradeDateBefore(cursor);
                if (prev == null)
                {
                    break;
                }
                cursor = prev;
            }
        }
        if (codes.isEmpty())
        {
            log.warn("最近 {} 个交易日无涨停记录，跳过", daysBack + 1);
            return;
        }
        log.info("待采集股票 {} 只（覆盖 {} 起 {} 个交易日）", codes.size(), cursor, daysBack + 1);

        // 2. 并行抓取当日分时并落库
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger empty = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<?>> futures = new ArrayList<>();
        try
        {
            for (String code : codes)
            {
                futures.add(pool.submit(() -> collectOne(code, day, ok, empty, fail, rows)));
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
                f.get(30, TimeUnit.SECONDS);
            }
            catch (Exception e)
            {
                log.debug("分时采集任务异常: {}", e.getMessage());
            }
        }
        log.info("落库完成：成功 {} 只，无数据 {} 只，失败 {} 只，写入 {} 行", ok.get(), empty.get(), fail.get(), rows.get());
    }

    private void collectOne(String code, LocalDate day, AtomicInteger ok, AtomicInteger empty,
                            AtomicInteger fail, AtomicInteger rows)
    {
        try
        {
            TencentKlineDataSource.MinuteSnapshot snap = tencentKlineDataSource.fetchMinuteSnapshot(
                    code, inferMarket(code), minuteType, day.toString());
            List<StockKline> bars = snap.getBars();
            if (bars == null || bars.isEmpty())
            {
                empty.incrementAndGet();
                return;
            }
            List<StockKline> list = new ArrayList<>(bars.size());
            for (StockKline b : bars)
            {
                if (b.getTradeTime() == null || b.getClosePrice() == null)
                {
                    continue;
                }
                b.setKlineType(minuteType);
                list.add(b);
            }
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
            log.debug("分时采集失败 code={}: {}", code, e.getMessage());
        }
    }

    private String inferMarket(String code)
    {
        return code.startsWith("6") ? "SH" : "SZ";
    }

    private LocalDate parseDate(String date)
    {
        if (date == null || date.trim().isEmpty())
        {
            return LocalDate.now();
        }
        try
        {
            return LocalDate.parse(date.trim());
        }
        catch (Exception e)
        {
            log.warn("日期参数 [{}] 解析失败，使用当前日期", date);
            return LocalDate.now();
        }
    }
}
