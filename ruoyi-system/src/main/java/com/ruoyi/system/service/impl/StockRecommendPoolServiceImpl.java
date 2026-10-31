package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockLockupSchedule;
import com.ruoyi.system.domain.stock.StockRecommendPool;
import com.ruoyi.system.domain.stock.StockRisingState;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockRecommendPoolMapper;
import com.ruoyi.system.mapper.StockRisingStateMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockLockupService;
import com.ruoyi.system.service.IStockRecommendPoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 次日推荐池实现
 *
 * 来源一：趋势池（stock_rising_state.status = 上升），按换手、抗追高、波段位置评分取前 N；
 * 来源二：涨停池（当日首板/二板），排除 ST / 创业 / 科创，按封板时间取前 N；
 * 两类候选都会剔除「未来 riskWindow 天内解禁比例 >= riskMinRatio」的股票。
 *
 * @author ruoyi
 */
@Service
public class StockRecommendPoolServiceImpl implements IStockRecommendPoolService
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendPoolServiceImpl.class);

    private static final String SOURCE_TREND = "TREND";
    private static final String SOURCE_LIMIT_UP = "LIMIT_UP";
    private static final String SOURCE_LOCKUP_GUIDED = "LOCKUP_GUIDED";
    private static final String STATUS_UP = "上升";
    private static final Pattern BOARD_PATTERN = Pattern.compile("(\\d+)板$");
    private static final double YI = 100000000d;
    private static final int DB_BATCH_SIZE = 200;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockRisingStateMapper stockRisingStateMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockRecommendPoolMapper stockRecommendPoolMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockLockupService stockLockupService;

    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String trendGroupName;

    @Value("${stock.recommend-pool.group-name:次日推荐池}")
    private String poolGroupName;

    @Value("${stock.recommend-pool.trend-size:30}")
    private int trendSize;

    @Value("${stock.recommend-pool.limit-up-size:30}")
    private int limitUpSize;

    @Value("${stock.recommend-pool.max-gain-5d:15}")
    private double maxGain5d;

    @Value("${stock.recommend-pool.max-bias-ma20:12}")
    private double maxBiasMa20;

    @Value("${stock.recommend-pool.min-turnover-rate:3}")
    private double minTurnoverRate;

    @Value("${stock.recommend-pool.small-cap-turnover-rate:5}")
    private double smallCapTurnoverRate;

    @Value("${stock.recommend-pool.large-cap-turnover-rate:1.5}")
    private double largeCapTurnoverRate;

    @Value("${stock.recommend-pool.small-cap-yi:50}")
    private double smallCapYi;

    @Value("${stock.recommend-pool.large-cap-yi:200}")
    private double largeCapYi;

    @Value("${stock.recommend-pool.exclude-st:true}")
    private boolean excludeSt;

    @Value("${stock.recommend-pool.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    @Value("${stock.lockup.risk-window-days:20}")
    private int lockupRiskWindowDays;

    @Value("${stock.lockup.risk-min-ratio:0.05}")
    private BigDecimal lockupRiskMinRatio;

    /** 解禁引导子信号：窗口开始天数 */
    @Value("${stock.lockup.guided.window-start-days:5}")
    private int guidedWindowStartDays;

    /** 解禁引导子信号：窗口结束天数 */
    @Value("${stock.lockup.guided.window-end-days:45}")
    private int guidedWindowEndDays;

    /** 解禁引导子信号：解禁比例下限 */
    @Value("${stock.lockup.guided.min-ratio:0.03}")
    private BigDecimal guidedMinRatio;

    /** 解禁引导子信号：解禁比例上限 */
    @Value("${stock.lockup.guided.max-ratio:0.20}")
    private BigDecimal guidedMaxRatio;

    /** 解禁引导子信号：最多入池数量 */
    @Value("${stock.lockup.guided.size:3}")
    private int guidedSize;

    @Override
    @Transactional
    public void buildPool(LocalDate date)
    {
        // 数据日 = 最近一个有日K的交易日（<= 请求日）。周末/节假日手动执行时不能用今天，
        // 否则涨停表、趋势状态、日K统计都会取不到数据。
        String maxTradeDate = stockKlineMapper.selectMaxTradeDateBefore(date.plusDays(1).toString());
        LocalDate dataDate = date;
        if (maxTradeDate != null && !maxTradeDate.isEmpty())
        {
            dataDate = LocalDate.parse(maxTradeDate);
        }
        LocalDate targetDate = nextTradingDay(dataDate);
        log.info("========== 生成次日推荐池: 请求日={}, 数据日={}, 目标交易日={} ==========",
                date, dataDate, targetDate);

        // 重跑覆盖
        stockRecommendPoolMapper.deleteByTargetDate(java.sql.Date.valueOf(targetDate));

        Set<String> riskCodes = stockLockupService.selectRiskCodes(
                dataDate, dataDate.plusDays(lockupRiskWindowDays), lockupRiskMinRatio);
        log.info("解禁风险过滤: 未来 {} 天内解禁比例 >= {} 的股票 {} 只",
                lockupRiskWindowDays, lockupRiskMinRatio, riskCodes.size());

        List<StockRecommendPool> pool = new ArrayList<>();
        pool.addAll(buildTrendPool(dataDate, riskCodes));
        pool.addAll(buildLimitUpPool(dataDate, riskCodes));
        pool.addAll(buildLockupGuidedPool(dataDate));

        if (pool.isEmpty())
        {
            log.warn("推荐池为空：趋势上升/涨停首二板/解禁引导均无候选（或全部被过滤）");
            return;
        }

        // 统一写入目标交易日（各来源只负责 code/source，不重复设置日期）
        java.sql.Date targetSqlDate = java.sql.Date.valueOf(targetDate);
        for (StockRecommendPool row : pool)
        {
            row.setTargetDate(targetSqlDate);
        }

        for (int i = 0; i < pool.size(); i += DB_BATCH_SIZE)
        {
            List<StockRecommendPool> batch = pool.subList(i, Math.min(i + DB_BATCH_SIZE, pool.size()));
            stockRecommendPoolMapper.batchUpsert(new ArrayList<>(batch));
        }

        long trendCount = pool.stream().filter(p -> SOURCE_TREND.equals(p.getSource())).count();
        long limitUpCount = pool.stream().filter(p -> SOURCE_LIMIT_UP.equals(p.getSource())).count();
        long guidedCount = pool.stream().filter(p -> SOURCE_LOCKUP_GUIDED.equals(p.getSource())).count();
        createPoolGroup(targetDate, pool, (int) trendCount, (int) limitUpCount, (int) guidedCount, riskCodes.size());

        log.info("推荐池生成完成: 趋势 {} 只, 涨停 {} 只, 解禁引导 {} 只, 合计 {} 只",
                trendCount, limitUpCount, guidedCount, pool.size());
    }

    @Override
    public List<StockRecommendPool> selectByTargetDate(LocalDate targetDate)
    {
        return stockRecommendPoolMapper.selectByTargetDate(java.sql.Date.valueOf(targetDate));
    }

    @Override
    public LocalDate latestTargetDate(LocalDate date)
    {
        Date latest = stockRecommendPoolMapper.selectLatestTargetDateBefore(java.sql.Date.valueOf(date));
        return latest == null ? null : new java.sql.Date(latest.getTime()).toLocalDate();
    }

    // ==================== 趋势来源 ====================

    private List<StockRecommendPool> buildTrendPool(LocalDate date, Set<String> riskCodes)
    {
        StockGroup group = findGroupByName(trendGroupName);
        if (group == null)
        {
            log.warn("未找到趋势积累池分组 [{}]，趋势来源为空", trendGroupName);
            return new ArrayList<>();
        }
        List<Stock> stocks = stockGroupService.selectStocksByGroupId(group.getId());
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("趋势积累池 [{}] 为空", trendGroupName);
            return new ArrayList<>();
        }

        Map<String, Stock> stockMap = new LinkedHashMap<>();
        for (Stock stock : stocks)
        {
            if (stock.getStockCode() != null)
            {
                stockMap.put(stock.getStockCode(), stock);
            }
        }
        List<String> codes = new ArrayList<>(stockMap.keySet());
        List<StockRisingState> states = stockRisingStateMapper.selectByStockCodes(codes);
        Map<String, StockRisingState> stateMap = new HashMap<>();
        if (states != null)
        {
            for (StockRisingState state : states)
            {
                if (state.getStockCode() != null)
                {
                    stateMap.put(state.getStockCode(), state);
                }
            }
        }

        Map<String, double[]> dailyStats = loadDailyStats(codes, date);
        List<Scored> scored = new ArrayList<>();
        int filtered = 0;
        for (String code : codes)
        {
            StockRisingState state = stateMap.get(code);
            if (state == null || !STATUS_UP.equals(state.getStatus()))
            {
                continue;
            }
            if (riskCodes.contains(code))
            {
                filtered++;
                continue;
            }
            Stock stock = stockMap.get(code);
            if (isLowLiquidity(stock))
            {
                continue;
            }
            double[] stat = dailyStats.get(code);
            if (stat != null)
            {
                if (!Double.isNaN(stat[0]) && stat[0] > maxGain5d)
                {
                    continue;
                }
                if (!Double.isNaN(stat[1]) && stat[1] > maxBiasMa20)
                {
                    continue;
                }
            }
            Scored item = new Scored();
            item.stock = stock;
            item.state = state;
            item.score = score(stock, state);
            scored.add(item);
        }

        scored.sort(Comparator.comparingDouble((Scored s) -> s.score).reversed());
        if (trendSize > 0 && scored.size() > trendSize)
        {
            scored = new ArrayList<>(scored.subList(0, trendSize));
        }

        List<StockRecommendPool> result = new ArrayList<>(scored.size());
        for (Scored item : scored)
        {
            StockRecommendPool row = new StockRecommendPool();
            row.setStockCode(item.stock.getStockCode());
            row.setStockName(item.stock.getStockName());
            row.setSource(SOURCE_TREND);
            row.setScore(BigDecimal.valueOf(item.score).setScale(4, java.math.RoundingMode.HALF_UP));
            row.setStatus(item.state.getStatus());
            row.setReason(String.format("趋势上升 · 换手%s%%",
                    item.stock.getTurnoverRate() == null ? "-" : item.stock.getTurnoverRate().toPlainString()));
            row.setExtra(String.format("{\"stopPrice\":%s,\"high\":%s,\"low\":%s}",
                    item.state.getStopPrice(), item.state.getHighPrice(), item.state.getLowPrice()));
            result.add(row);
        }

        log.info("趋势来源: 池内 {} 只, 上升 {} 只, 解禁剔除 {} 只, 入池 {} 只",
                stocks.size(), stateMap.size(), filtered, result.size());
        return result;
    }

    // ==================== 涨停来源 ====================

    private List<StockRecommendPool> buildLimitUpPool(LocalDate date, Set<String> riskCodes)
    {
        List<StockLimitUpRecord> records = stockLimitUpRecordMapper.selectByTradeDate(date.toString());
        if (records == null || records.isEmpty())
        {
            log.info("当日无涨停记录，涨停来源为空");
            return new ArrayList<>();
        }

        List<String> prefixes = parseExcludePrefixes();
        Map<String, LimitUpPick> pickMap = new LinkedHashMap<>();
        for (StockLimitUpRecord record : records)
        {
            String code = record.getSecuCode();
            if (code == null || code.isEmpty())
            {
                continue;
            }
            if (excludeSt && record.getSecuName() != null
                    && record.getSecuName().toUpperCase().contains("ST"))
            {
                continue;
            }
            if (startsWithAny(code, prefixes))
            {
                continue;
            }
            int board = boardOf(record.getUpNum());
            if (board != 1 && board != 2)
            {
                continue;
            }
            if (riskCodes.contains(code))
            {
                continue;
            }
            String sealTime = sealTimeOf(record.getTime());
            LimitUpPick exist = pickMap.get(code);
            if (exist == null)
            {
                LimitUpPick pick = new LimitUpPick();
                pick.code = code;
                pick.name = record.getSecuName();
                pick.sealTime = sealTime;
                pick.board = board;
                pickMap.put(code, pick);
            }
            else if (sealTime.compareTo(exist.sealTime) < 0)
            {
                exist.sealTime = sealTime;
            }
        }

        List<LimitUpPick> picks = new ArrayList<>(pickMap.values());
        picks.sort(Comparator.comparing(p -> p.sealTime));
        if (limitUpSize > 0 && picks.size() > limitUpSize)
        {
            picks = new ArrayList<>(picks.subList(0, limitUpSize));
        }

        List<StockRecommendPool> result = new ArrayList<>(picks.size());
        for (int i = 0; i < picks.size(); i++)
        {
            LimitUpPick pick = picks.get(i);
            StockRecommendPool row = new StockRecommendPool();
            row.setStockCode(pick.code);
            row.setStockName(pick.name);
            row.setSource(SOURCE_LIMIT_UP);
            row.setScore(BigDecimal.valueOf(picks.size() - i));
            row.setStatus(pick.board + "板");
            row.setReason(String.format("%d板 · 封板 %s", pick.board, pick.sealTime));
            row.setExtra(String.format("{\"board\":%d,\"sealTime\":\"%s\"}", pick.board, pick.sealTime));
            result.add(row);
        }

        log.info("涨停来源: 记录 {} 条, 首二板去重 {} 只, 入池 {} 只",
                records.size(), pickMap.size(), result.size());
        return result;
    }

    // ==================== 解禁引导来源 ====================

    /**
     * 解禁前「庄家引导拉升」子信号：
     * 不是所有解禁前上涨都值得参与，只挑同时满足以下特征的：
     *   - 解禁日在 5~45 天内，解禁比例 3%~20%
     *   - 10 日涨幅 0%~20%（温和上行，未加速）
     *   - 收盘 > MA20 且 MA5 > MA20（趋势刚转强，不要求完整多头）
     *   - 近 10 日上涨天数 >= 5（红多绿少）
     *   - 5 日均量 / 20 日均量 0.9~2.5（温和放量，不是爆量）
     *   - 近 10 日平均振幅 <= 6%，近 5 日无涨停（安静拉升）
     *   - 收盘距 20 日高点 <= 8%
     *   - 换手率 0.3%~15%（低换手是安静吸筹特征，不能按情绪票要求 2% 以上）
     * 该来源不套用 TREND/LIMIT_UP 的解禁风险过滤，因为它本身就是围绕解禁做文章。
     */
    private List<StockRecommendPool> buildLockupGuidedPool(LocalDate date)
    {
        List<StockLockupSchedule> schedules = stockLockupService.selectByFreeDateRange(
                date.plusDays(guidedWindowStartDays), date.plusDays(guidedWindowEndDays));
        if (schedules == null || schedules.isEmpty())
        {
            return new ArrayList<>();
        }

        Map<String, LockupAgg> aggMap = new HashMap<>();
        for (StockLockupSchedule schedule : schedules)
        {
            if (schedule.getStockCode() == null || schedule.getFreeRatio() == null)
            {
                continue;
            }
            LockupAgg agg = aggMap.computeIfAbsent(schedule.getStockCode(), k -> new LockupAgg());
            agg.code = schedule.getStockCode();
            agg.ratio = agg.ratio.add(schedule.getFreeRatio());
            if (schedule.getFreeDate() != null
                    && (agg.earliest == null || schedule.getFreeDate().before(agg.earliest)))
            {
                agg.earliest = schedule.getFreeDate();
            }
            if (agg.type == null && schedule.getSharesType() != null)
            {
                agg.type = schedule.getSharesType();
            }
        }

        List<String> codes = new ArrayList<>();
        for (Map.Entry<String, LockupAgg> entry : aggMap.entrySet())
        {
            BigDecimal ratio = entry.getValue().ratio;
            if (ratio.compareTo(guidedMinRatio) >= 0 && ratio.compareTo(guidedMaxRatio) <= 0)
            {
                codes.add(entry.getKey());
            }
        }
        if (codes.isEmpty())
        {
            log.info("解禁引导: 窗口内无比例 {}~{} 的解禁股票", guidedMinRatio, guidedMaxRatio);
            return new ArrayList<>();
        }

        Map<String, Stock> stockMap = new HashMap<>();
        List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
        if (stocks != null)
        {
            for (Stock stock : stocks)
            {
                if (stock.getStockCode() != null)
                {
                    stockMap.put(stock.getStockCode(), stock);
                }
            }
        }

        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(
                codes, "D", date.minusDays(50).toString(), date.toString());
        Map<String, List<StockKline>> byCode = new HashMap<>();
        if (klines != null)
        {
            for (StockKline kline : klines)
            {
                if (kline.getStockCode() != null && kline.getClosePrice() != null)
                {
                    byCode.computeIfAbsent(kline.getStockCode(), k -> new ArrayList<>()).add(kline);
                }
            }
        }

        List<GuidedPick> picks = new ArrayList<>();
        for (String code : codes)
        {
            List<StockKline> bars = byCode.get(code);
            if (bars == null || bars.size() < 25)
            {
                continue;
            }
            bars.sort(Comparator.comparing(StockKline::getTradeTime));
            int n = bars.size();
            double[] closes = new double[n];
            double[] volumes = new double[n];
            double[] highs = new double[n];
            double[] lows = new double[n];
            for (int i = 0; i < n; i++)
            {
                StockKline bar = bars.get(i);
                closes[i] = bar.getClosePrice().doubleValue();
                volumes[i] = bar.getVolume() == null ? 0 : bar.getVolume();
                highs[i] = bar.getHighPrice() == null ? closes[i] : bar.getHighPrice().doubleValue();
                lows[i] = bar.getLowPrice() == null ? closes[i] : bar.getLowPrice().doubleValue();
            }
            double close = closes[n - 1];
            double gain10 = (close / closes[n - 11] - 1) * 100;
            if (gain10 < 0 || gain10 > 20)
            {
                continue;
            }

            double ma5 = avg(closes, n - 5, n);
            double ma20 = avg(closes, n - 20, n);
            if (!(close > ma20 && ma5 > ma20))
            {
                continue;
            }

            int upDays = 0;
            for (int i = n - 10; i < n; i++)
            {
                if (closes[i] > closes[i - 1])
                {
                    upDays++;
                }
            }
            if (upDays < 5)
            {
                continue;
            }

            double vol5 = avg(volumes, n - 5, n);
            double vol20 = avg(volumes, n - 20, n);
            if (vol20 <= 0)
            {
                continue;
            }
            double volRatio = vol5 / vol20;
            if (volRatio < 0.9 || volRatio > 2.5)
            {
                continue;
            }

            double ampSum = 0;
            double maxGain5 = 0;
            for (int i = n - 10; i < n; i++)
            {
                double prev = closes[i - 1];
                if (prev <= 0)
                {
                    continue;
                }
                ampSum += (highs[i] - lows[i]) / prev * 100;
                if (i >= n - 5)
                {
                    maxGain5 = Math.max(maxGain5, (closes[i] / prev - 1) * 100);
                }
            }
            double avgAmp10 = ampSum / 10;
            if (avgAmp10 > 6.0 || maxGain5 >= 9.5)
            {
                continue;
            }

            double high20 = 0;
            for (int i = n - 20; i < n; i++)
            {
                high20 = Math.max(high20, highs[i]);
            }
            if (high20 <= 0)
            {
                continue;
            }
            double distHigh = (high20 - close) / high20 * 100;
            if (distHigh > 8.0)
            {
                continue;
            }

            Stock stock = stockMap.get(code);
            Double turnover = stock == null || stock.getTurnoverRate() == null
                    ? null : stock.getTurnoverRate().doubleValue();
            // 低换手是「安静吸筹」的特征，不能按情绪票的标准要求 2% 以上
            if (turnover != null && (turnover < 0.3 || turnover > 15.0))
            {
                continue;
            }

            double distMa20 = (close / ma20 - 1) * 100;
            double gainScore = clamp01(1 - Math.abs(gain10 - 8.0) / 15.0);
            double maScore = clamp01(1 - Math.abs(distMa20 - 3.0) / 8.0);
            double volScore = clamp01(1 - Math.abs(volRatio - 1.4) / 1.1);
            double upScore = upDays / 10.0;
            double highScore = clamp01(1 - distHigh / 8.0);
            double ratioScore = clamp01(aggMap.get(code).ratio.doubleValue() / 0.10);
            double score = 0.25 * gainScore + 0.20 * maScore + 0.20 * upScore
                    + 0.15 * volScore + 0.10 * highScore + 0.10 * ratioScore;

            GuidedPick pick = new GuidedPick();
            pick.code = code;
            pick.name = stock == null ? code : stock.getStockName();
            pick.unlockDate = aggMap.get(code).earliest;
            pick.ratio = aggMap.get(code).ratio;
            pick.gain10 = gain10;
            pick.distMa20 = distMa20;
            pick.volRatio = volRatio;
            pick.avgAmp10 = avgAmp10;
            pick.upDays = upDays;
            pick.score = score;
            picks.add(pick);
        }

        picks.sort(Comparator.comparingDouble((GuidedPick p) -> p.score).reversed());
        int qualified = picks.size();
        if (guidedSize > 0 && picks.size() > guidedSize)
        {
            picks = new ArrayList<>(picks.subList(0, guidedSize));
        }

        List<StockRecommendPool> result = new ArrayList<>(picks.size());
        for (GuidedPick pick : picks)
        {
            StockRecommendPool row = new StockRecommendPool();
            row.setStockCode(pick.code);
            row.setStockName(pick.name);
            row.setSource(SOURCE_LOCKUP_GUIDED);
            row.setScore(BigDecimal.valueOf(pick.score).setScale(4, java.math.RoundingMode.HALF_UP));
            row.setStatus("解禁引导");
            row.setReason(String.format("解禁%s · 比例%.1f%% · 10日%+.1f%%",
                    new java.text.SimpleDateFormat("yyyy-MM-dd").format(pick.unlockDate),
                    pick.ratio.doubleValue() * 100, pick.gain10));
            row.setExtra(String.format("{\"unlockDate\":\"%s\",\"freeRatio\":%s,\"gain10\":%.2f,"
                            + "\"distMa20\":%.2f,\"volRatio\":%.2f,\"avgAmp10\":%.2f,\"upDays\":%d}",
                    new java.text.SimpleDateFormat("yyyy-MM-dd").format(pick.unlockDate),
                    pick.ratio, pick.gain10, pick.distMa20, pick.volRatio, pick.avgAmp10, pick.upDays));
            result.add(row);
        }

        log.info("解禁引导: 窗口内解禁股 {} 只, 符合温和拉升特征 {} 只, 入池 {} 只",
                aggMap.size(), qualified, result.size());
        return result;
    }

    private double avg(double[] values, int from, int to)
    {
        double sum = 0;
        for (int i = from; i < to; i++)
        {
            sum += values[i];
        }
        return sum / (to - from);
    }

    private static class LockupAgg
    {
        private String code;
        private java.util.Date earliest;
        private BigDecimal ratio = BigDecimal.ZERO;
        private String type;
    }

    private static class GuidedPick
    {
        private String code;
        private String name;
        private java.util.Date unlockDate;
        private BigDecimal ratio;
        private double gain10;
        private double distMa20;
        private double volRatio;
        private double avgAmp10;
        private int upDays;
        private double score;
    }

    // ==================== 辅助 ====================

    private Map<String, double[]> loadDailyStats(List<String> codes, LocalDate date)
    {
        Map<String, double[]> result = new HashMap<>();
        if (codes == null || codes.isEmpty())
        {
            return result;
        }
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(
                codes, "D", date.minusDays(50).toString(), date.toString());
        if (klines == null || klines.isEmpty())
        {
            return result;
        }

        Map<String, List<StockKline>> byCode = new HashMap<>();
        for (StockKline kline : klines)
        {
            if (kline.getStockCode() == null || kline.getClosePrice() == null)
            {
                continue;
            }
            byCode.computeIfAbsent(kline.getStockCode(), k -> new ArrayList<>()).add(kline);
        }

        for (Map.Entry<String, List<StockKline>> entry : byCode.entrySet())
        {
            List<StockKline> list = entry.getValue();
            list.sort(Comparator.comparing(StockKline::getTradeTime));
            int n = list.size();
            double last = list.get(n - 1).getClosePrice().doubleValue();
            double gain5d = Double.NaN;
            if (n >= 6)
            {
                double base = list.get(n - 6).getClosePrice().doubleValue();
                if (base > 0)
                {
                    gain5d = (last / base - 1) * 100;
                }
            }
            double biasMa20 = Double.NaN;
            if (n >= 20)
            {
                double sum = 0;
                for (int i = n - 20; i < n; i++)
                {
                    sum += list.get(i).getClosePrice().doubleValue();
                }
                double ma20 = sum / 20;
                if (ma20 > 0)
                {
                    biasMa20 = (last / ma20 - 1) * 100;
                }
            }
            result.put(entry.getKey(), new double[]{gain5d, biasMa20});
        }
        return result;
    }

    private double score(Stock stock, StockRisingState state)
    {
        double price = stock.getCurrentPrice() == null ? 0 : stock.getCurrentPrice().doubleValue();
        double high = state.getHighPrice() == null ? 0 : state.getHighPrice();
        double low = state.getLowPrice() == null ? 0 : state.getLowPrice();
        double pos = (high > low && price > 0) ? clamp01((price - low) / (high - low)) : 0;

        double volumeRatio = stock.getVolumeRatio() == null ? 0 : stock.getVolumeRatio().doubleValue();
        double vol = clamp01(volumeRatio / 2.0);

        double turnover = stock.getTurnoverRate() == null ? 0 : stock.getTurnoverRate().doubleValue();
        double turn = clamp01(1 - Math.abs(turnover - 6.0) / 10.0);

        double change = stock.getChangeRate() == null ? 0 : stock.getChangeRate().doubleValue();
        double chg = clamp01(1 - Math.abs(change - 3.0) / 5.0);

        return 0.55 * pos + 0.20 * vol + 0.15 * turn + 0.10 * chg;
    }

    private boolean isLowLiquidity(Stock stock)
    {
        if (stock == null || stock.getCurrentPrice() == null
                || stock.getCurrentPrice().doubleValue() <= 0)
        {
            return true;
        }
        if (stock.getTurnoverRate() == null)
        {
            return false;
        }
        return stock.getTurnoverRate().doubleValue() < turnoverThreshold(stock);
    }

    private double turnoverThreshold(Stock stock)
    {
        BigDecimal cap = stock.getCircMarketCap();
        if (cap == null || cap.doubleValue() <= 0)
        {
            return minTurnoverRate;
        }
        double capYi = cap.doubleValue() / YI;
        if (capYi < smallCapYi)
        {
            return smallCapTurnoverRate;
        }
        if (capYi > largeCapYi)
        {
            return largeCapTurnoverRate;
        }
        return minTurnoverRate;
    }

    private double clamp01(double value)
    {
        return Math.max(0, Math.min(1, value));
    }

    private LocalDate nextTradingDay(LocalDate date)
    {
        LocalDate next = date.plusDays(1);
        while (TradingDayUtils.isWeekend(next))
        {
            next = next.plusDays(1);
        }
        return next;
    }

    private StockGroup findGroupByName(String name)
    {
        StockGroup query = new StockGroup();
        query.setGroupName(name);
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups == null)
        {
            return null;
        }
        for (StockGroup group : groups)
        {
            if (name.equals(group.getGroupName()))
            {
                return group;
            }
        }
        return null;
    }

    private void createPoolGroup(LocalDate targetDate, List<StockRecommendPool> pool,
                                 int trendCount, int limitUpCount, int guidedCount, int riskCount)
    {
        String name = poolGroupName + "_" + targetDate;
        Set<String> codes = new HashSet<>();
        for (StockRecommendPool row : pool)
        {
            codes.add(row.getStockCode());
        }
        String[] stockCodes = codes.toArray(new String[0]);
        String remark = String.format("%s（目标交易日 %s，趋势 %d 只 / 涨停 %d 只 / 解禁引导 %d 只，解禁风险剔除 %d 只）",
                name, targetDate, trendCount, limitUpCount, guidedCount, riskCount);

        StockGroup existing = findGroupByName(name);
        if (existing != null)
        {
            existing.setGroupName(name);
            existing.setStockCodes(stockCodes);
            existing.setRemark(remark);
            existing.setUpdateBy("system");
            stockGroupService.updateGroup(existing);
            log.info("覆盖推荐池分组 [{}]，{} 只", name, stockCodes.length);
        }
        else
        {
            StockGroup group = new StockGroup();
            group.setGroupName(name);
            group.setStockCodes(stockCodes);
            group.setCreateBy("system");
            group.setRemark(remark);
            stockGroupService.insertGroup(group);
            log.info("创建推荐池分组 [{}]，{} 只", name, stockCodes.length);
        }
    }

    private List<String> parseExcludePrefixes()
    {
        List<String> result = new ArrayList<>();
        if (excludeCodePrefixes == null || excludeCodePrefixes.trim().isEmpty())
        {
            return result;
        }
        for (String prefix : excludeCodePrefixes.split(","))
        {
            String trimmed = prefix.trim();
            if (!trimmed.isEmpty())
            {
                result.add(trimmed);
            }
        }
        return result;
    }

    private boolean startsWithAny(String code, List<String> prefixes)
    {
        for (String prefix : prefixes)
        {
            if (code.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }

    private int boardOf(String upNum)
    {
        if (upNum == null || upNum.trim().isEmpty())
        {
            return 1;
        }
        Matcher matcher = BOARD_PATTERN.matcher(upNum.trim());
        if (matcher.find())
        {
            try
            {
                return Integer.parseInt(matcher.group(1));
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return 1;
    }

    private String sealTimeOf(String time)
    {
        if (time == null)
        {
            return "99:99";
        }
        String trimmed = time.trim();
        if (trimmed.length() >= 16 && trimmed.charAt(10) == ' ')
        {
            return trimmed.substring(11, 16);
        }
        if (trimmed.length() >= 5 && trimmed.charAt(2) == ':')
        {
            return trimmed.substring(0, 5);
        }
        return "99:99";
    }

    private static class Scored
    {
        private Stock stock;
        private StockRisingState state;
        private double score;
    }

    private static class LimitUpPick
    {
        private String code;
        private String name;
        private String sealTime;
        private int board;
    }
}
