package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockStealthRallyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 偷偷上涨选股实现
 *
 * 算法流程（对每只股票）：
 *   1. 取最近 lookbackDays 个交易日日K线（不足则过滤）；
 *   2. 累计涨幅 = (末收-首收)/首收，要求落在 [minGain, maxGain]；
 *   3. 无涨停：最新涨停日期为空或早于回看起始日；
 *   4. 缩量：后半段均量 / 前半段均量 < shrinkRatio；
 *   5. 低换手率：日均换手率（成交量/流通股*100）< maxTurnover；
 *   6. 低振幅：日均振幅（(最高-最低)/昨收*100）< maxAmplitude。
 * 命中股票生成一个新分组。
 *
 * @author ruoyi
 */
@Service
public class StockStealthRallyServiceImpl implements IStockStealthRallyService
{
    private static final Logger log = LoggerFactory.getLogger(StockStealthRallyServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** 观察交易日数 */
    @Value("${stock.stealth-rally.lookback-days:30}")
    private int lookbackDays;

    /** 累计涨幅下限（%） */
    @Value("${stock.stealth-rally.min-gain:5.0}")
    private double minGain;

    /** 累计涨幅上限（%） */
    @Value("${stock.stealth-rally.max-gain:30.0}")
    private double maxGain;

    /** 缩量比：后段均量 / 前段均量 < 该值 */
    @Value("${stock.stealth-rally.shrink-ratio:0.9}")
    private double shrinkRatio;

    /** 日均换手率上限（%） */
    @Value("${stock.stealth-rally.max-turnover:3.0}")
    private double maxTurnover;

    /** 日均振幅上限（%） */
    @Value("${stock.stealth-rally.max-amplitude:4.0}")
    private double maxAmplitude;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.stealth-rally.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.stealth-rally.markets:SH,SZ,BJ}")
    private String markets;

    @Override
    public void execute()
    {
        LocalDate endDate = LocalDate.now();
        // 回看日历日需覆盖 lookbackDays 个交易日（约 5/7 换算 + 节假日缓冲）
        long pullCalendarDays = (long) Math.ceil(lookbackDays * 7.0 / 5.0) + 10;
        LocalDate startDate = endDate.minusDays(pullCalendarDays);
        log.info("========== 偷偷上涨选股开始 ==========");
        log.info("参数：endDate={} startDate={} lookbackDays={} minGain={} maxGain={} shrinkRatio={} maxTurnover={} maxAmplitude={} excludeSt={} markets={}",
                endDate, startDate, lookbackDays, minGain, maxGain, shrinkRatio, maxTurnover, maxAmplitude, excludeSt, markets);

        // 1. 查询候选股票基础信息
        List<Stock> stocks = stockMapper.selectStocksByMarkets(parseMarkets());
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("指定市场下无股票，跳过选股");
            return;
        }

        Map<String, Stock> stockMap = new LinkedHashMap<>();
        for (Stock s : stocks)
        {
            if (s.getStockCode() == null)
            {
                continue;
            }
            if (excludeSt && s.getStockName() != null && s.getStockName().toUpperCase().contains("ST"))
            {
                continue;
            }
            stockMap.put(s.getStockCode(), s);
        }
        if (stockMap.isEmpty())
        {
            log.warn("过滤后无候选股票，跳过选股");
            return;
        }
        log.info("候选股票 {} 只（已排除 ST {} 只）", stockMap.size(), stocks.size() - stockMap.size());

        // 2. 批量拉取日K线
        List<String> codes = new ArrayList<>(stockMap.keySet());
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate.toString(), endDate.toString());
        log.info("K线数据总量: {}", allKlines != null ? allKlines.size() : 0);

        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));

        // 3. 逐只判定
        List<Stock> matched = new ArrayList<>();
        for (Stock s : stockMap.values())
        {
            if (matches(s, klineMap.get(s.getStockCode()), endDate))
            {
                matched.add(s);
            }
        }
        log.info("符合条件的股票 {} 只", matched.size());

        // 4. 生成分组（无命中则不生成）
        if (matched.isEmpty())
        {
            log.warn("无符合条件的股票，任务结束");
            return;
        }

        createGroup(matched);
        log.info("========== 偷偷上涨选股完成 ==========");
    }

    /**
     * 判定单只股票是否满足「偷偷上涨」形态
     */
    private boolean matches(Stock stock, List<StockKline> klines, LocalDate endDate)
    {
        if (klines == null || klines.isEmpty())
        {
            return false;
        }

        // 按交易日升序排序，截取最近 lookbackDays 个交易日
        List<StockKline> list = new ArrayList<>(klines);
        list.sort(Comparator.comparing(StockKline::getTradeTime));
        if (list.size() < lookbackDays)
        {
            return false;
        }
        list = list.subList(list.size() - lookbackDays, list.size());
        int n = list.size();

        // 1. 累计涨幅：温和上涨
        Double firstClose = closeOf(list.get(0));
        Double lastClose = closeOf(list.get(n - 1));
        if (firstClose == null || lastClose == null || firstClose <= 0)
        {
            return false;
        }
        double gain = (lastClose - firstClose) / firstClose * 100;
        if (gain < minGain || gain > maxGain)
        {
            return false;
        }

        // 2. 无涨停：最新涨停日期为空或早于回看起始日
        Date lastLimitUpDate = stock.getLastLimitUpDate();
        if (lastLimitUpDate != null)
        {
            long cutoffDays = (long) Math.ceil(lookbackDays * 7.0 / 5.0);
            LocalDate cutoff = endDate.minusDays(cutoffDays);
            LocalDate luDate = lastLimitUpDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            if (!luDate.isBefore(cutoff))
            {
                return false;
            }
        }

        // 3. 缩量：后半段均量 / 前半段均量 < shrinkRatio
        int half = n / 2;
        double frontVol = avgVolume(list, 0, half);
        double backVol = avgVolume(list, half, n);
        if (frontVol <= 0 || backVol / frontVol >= shrinkRatio)
        {
            return false;
        }

        // 4. 低换手率：日均换手率（成交量/流通股*100）< maxTurnover
        BigDecimal circ = stock.getCirculatingShares();
        if (circ != null && circ.doubleValue() > 0 && avgTurnoverRate(list, circ.doubleValue()) > maxTurnover)
        {
            return false;
        }

        // 5. 低振幅：日均振幅（(最高-最低)/昨收*100）< maxAmplitude
        if (avgAmplitude(list) > maxAmplitude)
        {
            return false;
        }

        return true;
    }

    private Double closeOf(StockKline k)
    {
        return k.getClosePrice() == null ? null : k.getClosePrice().doubleValue();
    }

    /** 区间平均成交量（股） */
    private double avgVolume(List<StockKline> list, int from, int to)
    {
        double sum = 0;
        int cnt = 0;
        for (int i = from; i < to; i++)
        {
            Long v = list.get(i).getVolume();
            if (v != null)
            {
                sum += v.doubleValue();
                cnt++;
            }
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    /** 平均换手率（%），换手率 = 成交量 / 流通股 * 100 */
    private double avgTurnoverRate(List<StockKline> list, double circulatingShares)
    {
        double sum = 0;
        int cnt = 0;
        for (StockKline k : list)
        {
            Long v = k.getVolume();
            if (v != null)
            {
                sum += v.doubleValue() / circulatingShares * 100;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    /** 平均振幅（%），振幅 = (最高-最低) / 昨收 * 100 */
    private double avgAmplitude(List<StockKline> list)
    {
        double sum = 0;
        int cnt = 0;
        for (int i = 1; i < list.size(); i++)
        {
            StockKline k = list.get(i);
            Double high = k.getHighPrice() == null ? null : k.getHighPrice().doubleValue();
            Double low = k.getLowPrice() == null ? null : k.getLowPrice().doubleValue();
            Double prevClose = closeOf(list.get(i - 1));
            if (high != null && low != null && prevClose != null && prevClose > 0)
            {
                sum += (high - low) / prevClose * 100;
                cnt++;
            }
        }
        return cnt == 0 ? 0 : sum / cnt;
    }

    /**
     * 生成偷偷上涨分组
     */
    private void createGroup(List<Stock> matched)
    {
        String groupName = "偷偷上涨选股_" + LocalDate.now().toString();
        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockCodes(matched.stream().map(Stock::getStockCode).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("最近%d交易日累计涨幅%.1f%%~%.1f%%、无涨停、缩量比<%.2f、日均换手<%.1f%%、日均振幅<%.1f%%（命中%d只）",
                lookbackDays, minGain, maxGain, shrinkRatio, maxTurnover, maxAmplitude, matched.size()));

        stockGroupService.insertGroup(group);

        log.info("分组 [{}] 命中 {} 只", groupName, matched.size());
        for (Stock s : matched)
        {
            log.info("    {} {}", s.getStockCode(), s.getStockName());
        }
    }

    /**
     * 解析市场列表配置
     */
    private List<String> parseMarkets()
    {
        if (markets == null || markets.trim().isEmpty())
        {
            return Arrays.asList("SH", "SZ", "BJ");
        }
        return Arrays.stream(markets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
