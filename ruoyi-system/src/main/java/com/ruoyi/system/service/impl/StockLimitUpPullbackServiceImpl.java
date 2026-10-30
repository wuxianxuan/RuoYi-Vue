package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IMailNotifyService;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockLimitUpPullbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 首板/二板涨停后的「次日回调」尾盘推荐实现
 *
 * 与「涨停首二板_日期」分组的区别：
 *  - 那个分组只做「昨日涨停首二板」列表，供观察；
 *  - 本服务在次日 14:45 抓分时 K 线，判断回调当日的承接质量，只推符合条件的票。
 *
 * 筛选逻辑（全部基于 14:45 时点的分时数据 + 昨日涨停日 K 线）：
 *   1. 回调幅度在 [min-pullback, max-pullback]，避免深跌接飞刀（回测：≤-6% 的样本最差）；
 *   2. 分时收盘位置 ≥ min-close-pos，不在日内最低附近；
 *   3. 现价不低于分时均价线（VWAP）超过 min-vwap-dev，承接有力；
 *   4. 尾盘 30 分钟不跳水（≥ min-tail-change）；
 *   5. 涨停日振幅 ≤ max-limit-up-amplitude，且排除一字板（回测：一字板次日最差）；
 *   6. 流通市值 ≥ min-cap-yi 亿（微盘最差）；
 *   7. 换手率在 [min-turnover-rate, max-turnover-rate]。
 *
 * 通过后按承接评分排序，取前 top-n 只生成「首二板回调_日期」分组并邮件推送。
 *
 * @author ruoyi
 */
@Service
public class StockLimitUpPullbackServiceImpl implements IStockLimitUpPullbackService
{
    private static final Logger log = LoggerFactory.getLogger(StockLimitUpPullbackServiceImpl.class);

    private static final Pattern BOARD_PATTERN = Pattern.compile("(\\d+)板$");
    private static final double YI = 100000000d;

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private IMailNotifyService mailNotifyService;

    /** 分组名（固定名，不含日期） */
    @Value("${stock.limit-up-pullback.group-name:首二板回调}")
    private String groupName;

    /** 分时K线类型 */
    @Value("${stock.limit-up-pullback.minute-type:m5}")
    private String minuteType;

    /** 每日最多推荐数量（按承接评分取前N） */
    @Value("${stock.limit-up-pullback.top-n:10}")
    private int topN;

    /** 回调幅度下限(%)：低于该值视为深跌，不接 */
    @Value("${stock.limit-up-pullback.min-pullback:-4.0}")
    private double minPullback;

    /** 回调幅度上限(%)：高于该值不算回调 */
    @Value("${stock.limit-up-pullback.max-pullback:1.5}")
    private double maxPullback;

    /** 分时收盘位置下限(0~1) */
    @Value("${stock.limit-up-pullback.min-close-pos:0.45}")
    private double minClosePos;

    /** 现价相对分时均价线的偏离下限(%) */
    @Value("${stock.limit-up-pullback.min-vwap-dev:-0.5}")
    private double minVwapDev;

    /** 尾盘30分钟涨跌下限(%) */
    @Value("${stock.limit-up-pullback.min-tail-change:-0.5}")
    private double minTailChange;

    /** 涨停日振幅上限(%) */
    @Value("${stock.limit-up-pullback.max-limit-up-amplitude:12.0}")
    private double maxLimitUpAmplitude;

    /** 是否排除一字板 */
    @Value("${stock.limit-up-pullback.exclude-one-line:true}")
    private boolean excludeOneLine;

    /** 流通市值下限(亿元) */
    @Value("${stock.limit-up-pullback.min-cap-yi:30}")
    private double minCapYi;

    /** 换手率下限(%) */
    @Value("${stock.limit-up-pullback.min-turnover-rate:3.0}")
    private double minTurnoverRate;

    /** 换手率上限(%) */
    @Value("${stock.limit-up-pullback.max-turnover-rate:20.0}")
    private double maxTurnoverRate;

    /** 尾盘30分钟对应的5分钟bar数量 */
    @Value("${stock.limit-up-pullback.tail-bars:6}")
    private int tailBars;

    /** 并行抓取线程数 */
    @Value("${stock.limit-up-pullback.thread-pool-size:8}")
    private int threadPoolSize;

    /** 邮件主题前缀 */
    @Value("${stock.notify.subject:尾盘推荐}")
    private String notifySubject;

    @Override
    public void execute(String date)
    {
        LocalDate endDate = parseDate(date);
        log.info("========== 首二板回调推荐开始 ==========");
        log.info("参数：endDate={} 回调区间=[{},{}]% 收盘位>={} VWAP偏离>={}% 尾盘>={}% 振幅<={}% 市值>={}亿 换手=[{},{}]% 取前{}只",
                endDate, minPullback, maxPullback, minClosePos, minVwapDev, minTailChange,
                maxLimitUpAmplitude, minCapYi, minTurnoverRate, maxTurnoverRate, topN);

        String dailyGroupName = groupName + "_" + endDate;
        if (findGroupByName(dailyGroupName) != null)
        {
            log.warn("分组 [{}] 已存在，跳过本次执行；如需重算请先删除该分组", dailyGroupName);
            return;
        }

        // 1. 昨日（涨停日）
        String prevDateStr = stockKlineMapper.selectMaxTradeDateBefore(endDate.toString());
        if (prevDateStr == null)
        {
            log.warn("未找到前一交易日，跳过");
            return;
        }
        LocalDate prevDate = LocalDate.parse(prevDateStr);

        // 2. 昨日首板/二板
        List<String> codes = loadLimitUpCodes(prevDate);
        if (codes.isEmpty())
        {
            log.warn("昨日 [{}] 无首板/二板，跳过", prevDate);
            return;
        }
        log.info("昨日 [{}] 首二板 {} 只", prevDate, codes.size());

        // 3. 预加载：昨日K线（昨收/振幅/一字板）、股票基础（名称/流通市值）
        Map<String, StockKline> dayMap = loadDayKlines(codes, prevDate);
        Map<String, Stock> stockMap = loadStocks();
        log.info("预加载：昨日K线 {} 只，股票基础 {} 只", dayMap.size(), stockMap.size());

        // 4. 并行抓分时并计算
        Map<String, AtomicInteger> rejects = new ConcurrentHashMap<>();
        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<Result>> futures = new ArrayList<>();
        try
        {
            for (String code : codes)
            {
                futures.add(pool.submit(() -> analyze(code, endDate, prevDate, dayMap.get(code),
                        stockMap.get(code), rejects)));
            }
        }
        finally
        {
            pool.shutdown();
        }
        for (Future<Result> f : futures)
        {
            try
            {
                Result r = f.get(30, TimeUnit.SECONDS);
                if (r != null)
                {
                    results.add(r);
                }
            }
            catch (Exception e)
            {
                rejects.computeIfAbsent("抓取超时/失败", k -> new AtomicInteger()).incrementAndGet();
                log.debug("首二板分析失败: {}", e.getMessage());
            }
        }

        String funnel = formatRejects(rejects);
        log.info("分析完成：命中 {} 只；过滤分布：{}", results.size(), funnel);
        if (results.isEmpty())
        {
            log.warn("无符合条件的首二板回调，不生成分组");
            return;
        }

        results.sort(Comparator.comparingDouble((Result r) -> r.score).reversed());
        List<Result> picked = results;
        if (topN > 0 && results.size() > topN)
        {
            picked = new ArrayList<>(results.subList(0, topN));
            log.info("命中 {} 只，按承接评分取前 {} 只", results.size(), topN);
        }

        createGroup(picked, endDate, prevDate, results.size());
        notifyByEmail(picked, endDate, stockMap);
        log.info("========== 首二板回调推荐完成 ==========");
    }

    /** 昨日首板/二板（排除 ST / 创业板 / 科创板） */
    private List<String> loadLimitUpCodes(LocalDate prevDate)
    {
        List<StockLimitUpRecord> records = stockLimitUpRecordMapper.selectByTradeDate(prevDate.toString());
        if (records == null)
        {
            return new ArrayList<>();
        }
        List<String> codes = new ArrayList<>();
        for (StockLimitUpRecord r : records)
        {
            String code = r.getSecuCode();
            if (code == null || code.isEmpty() || codes.contains(code))
            {
                continue;
            }
            if (r.getSecuName() != null && r.getSecuName().toUpperCase().contains("ST"))
            {
                continue;
            }
            if (code.startsWith("300") || code.startsWith("301") || code.startsWith("688") || code.startsWith("689"))
            {
                continue;
            }
            int board = boardOf(r.getUpNum());
            if (board == 1 || board == 2)
            {
                codes.add(code);
            }
        }
        return codes;
    }

    private Map<String, StockKline> loadDayKlines(List<String> codes, LocalDate prevDate)
    {
        Map<String, StockKline> map = new HashMap<>();
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D",
                prevDate.minusDays(15).toString(), prevDate.toString());
        if (klines == null)
        {
            return map;
        }
        Map<String, List<StockKline>> byCode = new HashMap<>();
        for (StockKline k : klines)
        {
            if (k.getStockCode() != null && k.getTradeTime() != null)
            {
                byCode.computeIfAbsent(k.getStockCode(), c -> new ArrayList<>()).add(k);
            }
        }
        for (Map.Entry<String, List<StockKline>> e : byCode.entrySet())
        {
            List<StockKline> list = e.getValue();
            list.sort(Comparator.comparing(StockKline::getTradeTime));
            StockKline last = list.get(list.size() - 1);
            if (last.getTradeTime() != null && toLocalDate(last.getTradeTime()).equals(prevDate))
            {
                map.put(e.getKey(), last);
            }
        }
        return map;
    }

    private Map<String, Stock> loadStocks()
    {
        Map<String, Stock> map = new HashMap<>();
        List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
        if (stocks != null)
        {
            for (Stock s : stocks)
            {
                if (s.getStockCode() != null)
                {
                    map.put(s.getStockCode(), s);
                }
            }
        }
        return map;
    }

    /**
     * 单只股票：抓当日分时，计算回调质量并过滤
     */
    private Result analyze(String code, LocalDate endDate, LocalDate prevDate, StockKline dayBar,
                           Stock stock, Map<String, AtomicInteger> rejects)
    {
        try
        {
            if (dayBar == null || dayBar.getClosePrice() == null)
            {
                return reject(rejects, "无昨日K线");
            }
            TencentKlineDataSource.MinuteSnapshot snap = tencentKlineDataSource.fetchMinuteSnapshot(
                    code, inferMarket(code), minuteType, endDate.toString());
            List<StockKline> bars = snap.getBars() == null ? new ArrayList<>() : snap.getBars().stream()
                    .filter(k -> k.getTradeTime() != null && k.getClosePrice() != null
                            && k.getHighPrice() != null && k.getLowPrice() != null)
                    .sorted(Comparator.comparing(StockKline::getTradeTime))
                    .collect(Collectors.toList());
            if (bars.size() < tailBars + 1)
            {
                return reject(rejects, "数据不足");
            }

            double prevClose = dayBar.getClosePrice().doubleValue();
            if (prevClose <= 0)
            {
                return reject(rejects, "无昨收");
            }

            StockKline last = bars.get(bars.size() - 1);
            double price = last.getClosePrice().doubleValue();
            double dayHigh = -Double.MAX_VALUE;
            double dayLow = Double.MAX_VALUE;
            double pv = 0;
            double vv = 0;
            for (StockKline k : bars)
            {
                dayHigh = Math.max(dayHigh, k.getHighPrice().doubleValue());
                dayLow = Math.min(dayLow, k.getLowPrice().doubleValue());
                double v = k.getVolume() == null ? 0 : k.getVolume();
                pv += k.getClosePrice().doubleValue() * v;
                vv += v;
            }
            if (!(dayHigh > dayLow) || vv <= 0)
            {
                return reject(rejects, "数据不足");
            }
            double vwap = pv / vv;
            double pullback = (price - prevClose) / prevClose * 100;
            double closePos = (price - dayLow) / (dayHigh - dayLow);
            double vwapDev = (price - vwap) / vwap * 100;

            double tailStart = bars.get(bars.size() - tailBars - 1).getClosePrice().doubleValue();
            double tailChange = tailStart > 0 ? (price - tailStart) / tailStart * 100 : 0;

            // 涨停日特征
            double dHigh = dayBar.getHighPrice() == null ? 0 : dayBar.getHighPrice().doubleValue();
            double dLow = dayBar.getLowPrice() == null ? 0 : dayBar.getLowPrice().doubleValue();
            double dAmp = prevClose > 0 ? (dHigh - dLow) / prevClose * 100 : 0;
            boolean oneLine = dHigh > 0 && Math.abs(dHigh - dLow) < 1e-6;
            double capYi = stock != null && stock.getCircMarketCap() != null
                    ? stock.getCircMarketCap().doubleValue() / YI : 0;

            // 换手率/量比优先用分时接口的实时快照
            Double turnover = snap.getTurnoverRate();
            if (turnover == null && stock != null && stock.getCirculatingShares() != null
                    && stock.getCirculatingShares().doubleValue() > 0 && vv > 0)
            {
                turnover = vv * 100.0 / stock.getCirculatingShares().doubleValue();
            }

            // ---------- 过滤 ----------
            if (excludeOneLine && oneLine)
            {
                return reject(rejects, "一字板");
            }
            if (dAmp > maxLimitUpAmplitude)
            {
                return reject(rejects, "涨停日振幅过大");
            }
            if (capYi > 0 && capYi < minCapYi)
            {
                return reject(rejects, "市值过小");
            }
            if (pullback < minPullback)
            {
                return reject(rejects, "深跌不接");
            }
            if (pullback > maxPullback)
            {
                return reject(rejects, "未回调");
            }
            if (turnover != null && (turnover < minTurnoverRate || turnover > maxTurnoverRate))
            {
                return reject(rejects, "换手不达标");
            }
            if (closePos < minClosePos)
            {
                return reject(rejects, "收盘位置偏低");
            }
            if (vwapDev < minVwapDev)
            {
                return reject(rejects, "弱于分时均价");
            }
            if (tailChange < minTailChange)
            {
                return reject(rejects, "尾盘跳水");
            }

            // ---------- 评分 ----------
            double vwapScore = clamp01(1 + vwapDev / 1.5);
            double pullbackScore = clamp01(1 - Math.abs(pullback + 1.5) / 3.0);
            double tailScore = clamp01((tailChange + 0.5) / 2.0);
            double turnScore = turnover == null ? 0.5 : clamp01(1 - Math.abs(turnover - 6.0) / 10.0);
            double score = 0.30 * closePos + 0.25 * vwapScore + 0.20 * pullbackScore
                    + 0.15 * tailScore + 0.10 * turnScore;

            Result r = new Result();
            r.code = code;
            r.name = stock == null ? code : stock.getStockName();
            r.price = price;
            r.prevClose = prevClose;
            r.pullback = pullback;
            r.closePos = closePos;
            r.vwapDev = vwapDev;
            r.tailChange = tailChange;
            r.turnover = turnover;
            r.score = score;
            return r;
        }
        catch (Exception e)
        {
            log.debug("首二板分析失败 code={}, error={}", code, e.getMessage());
            return reject(rejects, "抓取失败");
        }
    }

    private void createGroup(List<Result> picked, LocalDate endDate, LocalDate prevDate, int hitCount)
    {
        String dailyGroupName = groupName + "_" + endDate;
        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(picked.stream().map(r -> r.code).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("涨停日%s；回调%.1f~%.1f%%+收盘位≥%.2f+VWAP偏离≥%.1f%%+尾盘30m≥%.1f%%+振幅≤%.0f%%+市值≥%.0f亿；命中%d取前%d",
                prevDate, minPullback, maxPullback, minClosePos, minVwapDev, minTailChange,
                maxLimitUpAmplitude, minCapYi, hitCount, picked.size()));
        stockGroupService.insertGroup(group);

        log.info("生成分组 [{}]，推荐 {} 只（命中 {}）", dailyGroupName, picked.size(), hitCount);
        int rank = 1;
        for (Result r : picked)
        {
            log.info("    {}. {} {} 回调{}% 收盘位{} VWAP偏离{}% 尾盘{}% 换手{} 评分{}",
                    rank++, r.code, r.name, fmt(r.pullback), fmt(r.closePos), fmt(r.vwapDev),
                    fmt(r.tailChange), r.turnover == null ? "-" : fmt(r.turnover), fmt(r.score));
        }
    }

    private void notifyByEmail(List<Result> picked, LocalDate endDate, Map<String, Stock> stockMap)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("首二板回调 ").append(picked.size()).append(" 只（涨停日次日 14:45 回调承接筛选）：\n");
        int i = 1;
        for (Result r : picked)
        {
            sb.append(i++).append(". ").append(r.name).append("（").append(r.code).append("）")
                    .append("  昨收").append(fmt(r.prevClose)).append("  现价").append(fmt(r.price))
                    .append("  回调").append(fmt(r.pullback)).append("%")
                    .append("  收盘位").append(fmt(r.closePos))
                    .append("  VWAP偏离").append(fmt(r.vwapDev)).append("%")
                    .append("  换手").append(r.turnover == null ? "-" : fmt(r.turnover) + "%")
                    .append("\n");
        }
        mailNotifyService.send("首二板回调 " + endDate, sb.toString());
    }

    private String formatRejects(Map<String, AtomicInteger> rejects)
    {
        if (rejects.isEmpty())
        {
            return "无";
        }
        return rejects.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().get(), a.getValue().get()))
                .map(e -> e.getKey() + "=" + e.getValue().get())
                .collect(Collectors.joining("，"));
    }

    private Result reject(Map<String, AtomicInteger> rejects, String reason)
    {
        rejects.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        return null;
    }

    private double clamp01(double v)
    {
        return Math.max(0, Math.min(1, v));
    }

    private String fmt(double v)
    {
        return String.format("%.2f", v);
    }

    private StockGroup findGroupByName(String name)
    {
        StockGroup query = new StockGroup();
        query.setGroupName(name);
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups != null)
        {
            for (StockGroup g : groups)
            {
                if (name.equals(g.getGroupName()))
                {
                    return g;
                }
            }
        }
        return null;
    }

    private int boardOf(String upNum)
    {
        if (upNum == null || upNum.trim().isEmpty())
        {
            return 1;
        }
        Matcher m = BOARD_PATTERN.matcher(upNum.trim());
        if (m.find())
        {
            try
            {
                return Integer.parseInt(m.group(1));
            }
            catch (NumberFormatException e)
            {
                return 0;
            }
        }
        return 0;
    }

    private String inferMarket(String code)
    {
        return code.startsWith("6") ? "SH" : "SZ";
    }

    private LocalDate toLocalDate(java.util.Date date)
    {
        return java.time.Instant.ofEpochMilli(date.getTime()).atZone(java.time.ZoneId.systemDefault()).toLocalDate();
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

    private static class Result
    {
        private String code;
        private String name;
        private double price;
        private double prevClose;
        private double pullback;
        private double closePos;
        private double vwapDev;
        private double tailChange;
        private Double turnover;
        private double score;
    }
}
