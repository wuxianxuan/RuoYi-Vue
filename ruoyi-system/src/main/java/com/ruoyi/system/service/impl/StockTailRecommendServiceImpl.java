package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockRecommendPool;
import com.ruoyi.system.domain.stock.StockTailRecommendRecord;
import com.ruoyi.system.domain.stock.StockTailRecommendRun;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockTailRecommendRecordMapper;
import com.ruoyi.system.mapper.StockTailRecommendRunMapper;
import com.ruoyi.system.service.IMailNotifyService;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockRecommendPoolService;
import com.ruoyi.system.service.IStockTailRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 尾盘推荐实现
 *
 * 每个交易日下午 14:45 运行（收盘前），基于昨日分组（上升/震荡/涨停首二板）的股票，
 * 拉取当日分时（5分钟）K线，按「尾盘可买 + 不追高」口径筛选：
 *
 * 一、先排风险（任一不满足直接淘汰）：
 *   1. 未涨停（当日涨幅 < maxGain）——涨停封死的尾盘买不进；
 *   2. 当日涨幅不超过 maxCloseGain——已经涨太多的不追；
 *   3. 高开不超过 maxOpenGain——跳空高开的不追；
 *   4. 近5日累计涨幅不超过 maxGain5d——已经涨透的不接；
 *   5. 相对 MA5 乖离不超过 maxBiasMa5——离5日线太远，回调概率大；
 *   6. 换手率在 [minTurnoverRate, maxTurnoverRate]、量比在 [minVolumeRatio, maxVolumeRatio]——
 *      太冷没流动性，过热是分歧/接盘；
 *   7. 当日成交额不低于 minTurnoverAmount（亿元）——流动性过滤，由分时成交量估算。
 *
 * 二、再看强度：
 *   8. 当日上涨（涨幅 >= minGain）且收盘贴近阶段高点（收盘位 >= minClosePos）；
 *   9. 尾盘不跳水（尾盘30分钟涨跌 >= minTailChange）。
 *
 * 三、命中后按综合评分（强度40% + 尾盘25% + 距离5日线20% + 涨幅适中15%）降序，
 *    只取前 top-n 只生成「尾盘推荐_日期」分组并邮件推送，供 15:00 收盘前买入。
 *
 * 数据不足（分时 bar 不够、缺昨收、缺历史日K）一律判失败并计入过滤分布，
 * 不再用默认值放行，避免手工提前触发时口径悄悄失真。
 *
 * @author ruoyi
 */
@Service
public class StockTailRecommendServiceImpl implements IStockTailRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockTailRecommendServiceImpl.class);

    private static final double YI = 100000000d;

    private static final String SOURCE_TREND = "TREND";
    private static final String SOURCE_LIMIT_UP = "LIMIT_UP";
    private static final String SOURCE_LOCKUP_GUIDED = "LOCKUP_GUIDED";

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockTailRecommendRecordMapper recommendRecordMapper;

    @Autowired
    private StockTailRecommendRunMapper recommendRunMapper;

    @Autowired
    private IMailNotifyService mailNotifyService;

    /** 规则版本：写入实验记录表，便于不同参数组合之间对比 */
    @Value("${stock.tail-recommend.rule-version:v2}")
    private String ruleVersion;

    /** 邮件主题前缀 */
    @Value("${stock.notify.subject:尾盘推荐}")
    private String notifySubject;

    /** 目标分组名（固定名，不含日期） */
    @Value("${stock.tail-recommend.group-name:尾盘推荐}")
    private String groupName;

    /** 分时K线类型 */
    @Value("${stock.tail-recommend.minute-type:m5}")
    private String minuteType;

    /** 未涨停阈值(%)：当日涨幅 >= 该值视为涨停，尾盘买不进 */
    @Value("${stock.tail-recommend.max-gain:9.5}")
    private double maxGain;

    /** 当日涨幅上限(%)：超过视为已经涨透，不再追高 */
    @Value("${stock.tail-recommend.max-close-gain:7.0}")
    private double maxCloseGain;

    /** 当日涨幅下限(%)：至少小幅上涨 */
    @Value("${stock.tail-recommend.min-gain:0.5}")
    private double minGain;

    /** 高开上限(%)：开盘涨幅超过该值视为跳空追高 */
    @Value("${stock.tail-recommend.max-open-gain:3.0}")
    private double maxOpenGain;

    /** 收盘位置下限(0~1)：收盘价相对当日[最低,最高]区间的比例 */
    @Value("${stock.tail-recommend.min-close-pos:0.7}")
    private double minClosePos;

    /** 尾盘30分钟涨跌下限(%)：负值允许小幅回落，过滤真正的尾盘跳水 */
    @Value("${stock.tail-recommend.min-tail-change:-0.5}")
    private double minTailChange;

    /** 量比下限：放量确认 */
    @Value("${stock.tail-recommend.min-volume-ratio:1.2}")
    private double minVolumeRatio;

    /** 量比上限：过度放量说明分歧大，次日容易回落 */
    @Value("${stock.tail-recommend.max-volume-ratio:3.0}")
    private double maxVolumeRatio;

    /** 换手率下限(%)：低于该值流动性不足 */
    @Value("${stock.tail-recommend.min-turnover-rate:3.0}")
    private double minTurnoverRate;

    /** 换手率上限(%)：高于该值多为高位分歧/接盘 */
    @Value("${stock.tail-recommend.max-turnover-rate:15.0}")
    private double maxTurnoverRate;

    /** 近5日累计涨幅上限(%)：截至昨日的区间涨幅，超过视为已透支 */
    @Value("${stock.tail-recommend.max-gain-5d:15.0}")
    private double maxGain5d;

    /** 相对MA5乖离上限(%)：现价偏离5日均线过远，回调概率大 */
    @Value("${stock.tail-recommend.max-bias-ma5:5.0}")
    private double maxBiasMa5;

    /** 当日成交额下限(亿元)：低于该值不推（流动性差、易被砸） */
    @Value("${stock.tail-recommend.min-turnover-amount:1.0}")
    private double minTurnoverAmount;

    /** 理想涨幅(%)：评分时涨幅最优点，偏离越远得分越低 */
    @Value("${stock.tail-recommend.ideal-gain:2.5}")
    private double idealGain;

    /** 趋势来源最终推荐名额 */
    @Value("${stock.tail-recommend.trend-quota:5}")
    private int trendQuota;

    /** 涨停首二板来源最终推荐名额 */
    @Value("${stock.tail-recommend.limit-up-quota:5}")
    private int limitUpQuota;

    /** 解禁引导来源最终推荐名额（实验性，给 2 个名额蹭一波） */
    @Value("${stock.tail-recommend.lockup-quota:2}")
    private int lockupQuota;

    @Autowired
    private IStockRecommendPoolService stockRecommendPoolService;

    @Autowired
    private LimitUpPullbackAnalyzer limitUpPullbackAnalyzer;

    /** 并行抓取分时K线的线程数 */
    @Value("${stock.tail-recommend.thread-pool-size:16}")
    private int threadPoolSize;

    /** 尾盘30分钟对应的5分钟bar数量（默认6根） */
    @Value("${stock.tail-recommend.tail-bars:6}")
    private int tailBars;

    @Override
    public void execute(String date)
    {
        LocalDate endDate = parseDate(date);
        log.info("========== 尾盘推荐开始 ==========");
        log.info("参数：endDate={} minuteType={} 涨幅=[{},{}%) 高开<={} 收盘位>={} 尾盘>={} 量比=[{},{}] 换手=[{},{}]% 5日涨幅<={}% 乖离<={}% 成交额>={}亿 名额=趋势{}+涨停{}只",
                endDate, minuteType, minGain, maxCloseGain, maxOpenGain, minClosePos, minTailChange,
                minVolumeRatio, maxVolumeRatio, minTurnoverRate, maxTurnoverRate,
                maxGain5d, maxBiasMa5, minTurnoverAmount, trendQuota, limitUpQuota);

        // 0. 幂等：当日分组已存在则整轮跳过，避免重复抓取行情、重复发邮件
        String dailyGroupName = groupName + "_" + endDate;
        if (findGroupByName(dailyGroupName) != null)
        {
            log.warn("分组 [{}] 已存在，跳过本次执行；如需重算请先删除该分组", dailyGroupName);
            return;
        }

        // 1. 确定前一交易日
        String prevDateStr = stockKlineMapper.selectMaxTradeDateBefore(endDate.toString());
        if (prevDateStr == null)
        {
            log.warn("未找到前一交易日，跳过");
            return;
        }
        LocalDate prevDate = LocalDate.parse(prevDateStr);
        long gapDays = ChronoUnit.DAYS.between(prevDate, endDate);
        log.info("前一交易日 = {}（与执行日相隔 {} 天）", prevDate, gapDays);
        if (gapDays > 4)
        {
            log.warn("基准日 [{}] 与执行日相隔 {} 天（长假或日K同步缺失），涨幅口径可能失真，请留意", prevDate, gapDays);
        }

        // 1.1 读取推荐池（119 盘后生成）：优先今日，缺失时回退最近一个池并校验新鲜度
        List<StockRecommendPool> poolRows = stockRecommendPoolService.selectByTargetDate(endDate);
        if (poolRows == null || poolRows.isEmpty())
        {
            LocalDate latest = stockRecommendPoolService.latestTargetDate(endDate);
            if (latest == null)
            {
                String msg = "推荐池为空，请先执行 stockRecommendPoolTask 生成次日推荐池";
                log.error(msg);
                throw new IllegalStateException(msg);
            }
            long gap = ChronoUnit.DAYS.between(latest, endDate);
            if (gap > 4)
            {
                String msg = String.format("最近推荐池生成于 %s，距执行日 %s 已 %d 天，池子过旧；请先执行 stockRecommendPoolTask",
                        latest, endDate, gap);
                log.error(msg);
                throw new IllegalStateException(msg);
            }
            log.warn("今日推荐池为空，回退使用最近推荐池 {}（相隔 {} 天）", latest, gap);
            poolRows = stockRecommendPoolService.selectByTargetDate(latest);
        }

        // 2. 候选去重：涨停 > 解禁引导 > 趋势
        Map<String, StockRecommendPool> candidateMap = new LinkedHashMap<>();
        for (StockRecommendPool row : poolRows)
        {
            if (row.getStockCode() == null || row.getStockCode().isEmpty())
            {
                continue;
            }
            StockRecommendPool exist = candidateMap.get(row.getStockCode());
            if (exist == null || sourcePriority(row.getSource()) > sourcePriority(exist.getSource()))
            {
                candidateMap.put(row.getStockCode(), row);
            }
        }
        if (candidateMap.isEmpty())
        {
            log.warn("推荐池无有效候选，跳过");
            return;
        }
        List<String> codes = new ArrayList<>(candidateMap.keySet());
        long trendCandidates = candidateMap.values().stream()
                .filter(r -> SOURCE_TREND.equals(r.getSource())).count();
        long limitUpCandidates = candidateMap.values().stream()
                .filter(r -> SOURCE_LIMIT_UP.equals(r.getSource())).count();
        long guidedCandidates = candidateMap.values().stream()
                .filter(r -> SOURCE_LOCKUP_GUIDED.equals(r.getSource())).count();
        log.info("推荐池候选 {} 只（趋势 {} / 涨停 {} / 解禁引导 {}）",
                codes.size(), trendCandidates, limitUpCandidates, guidedCandidates);

        // 3. 预加载昨收、当日快照、历史日K；涨停来源额外加载涨停日日K与股票快照
        Map<String, Double> prevCloseMap = loadPrevClose(codes, prevDate);
        Map<String, StockDailyQuote> quoteMap = loadQuote(endDate);
        Map<String, DailyStat> dailyStatMap = loadDailyStats(codes, prevDate);
        List<String> limitUpCodes = candidateMap.values().stream()
                .filter(r -> SOURCE_LIMIT_UP.equals(r.getSource()))
                .map(StockRecommendPool::getStockCode)
                .collect(Collectors.toList());
        Map<String, StockKline> limitUpDayBars = loadLimitUpDayBars(limitUpCodes, prevDate);
        Map<String, Stock> stockMap = loadStocks();
        log.info("预加载：昨收 {} 只，当日快照 {} 条，历史日K {} 只，涨停日K {} 只",
                prevCloseMap.size(), quoteMap.size(), dailyStatMap.size(), limitUpDayBars.size());

        // 4. 并行抓取当日分时K线：趋势走尾盘规则，涨停首二板走回调承接规则
        Map<String, AtomicInteger> rejects = new ConcurrentHashMap<>();
        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<Result>> futures = new ArrayList<>();
        try
        {
            for (StockRecommendPool candidate : candidateMap.values())
            {
                futures.add(pool.submit(() -> analyzeCandidate(candidate, endDate, prevDate,
                        prevCloseMap, quoteMap, dailyStatMap, limitUpDayBars, stockMap, rejects)));
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
                log.debug("尾盘分析任务失败: {}", e.getMessage());
            }
        }
        log.info("分析完成：有可用分时数据的候选 {} 只", results.size());

        // 5. 按来源分别排序、各取固定名额（不跨来源补位，保证来源对比干净）
        List<Result> trendPassed = results.stream()
                .filter(r -> r.rejectReason == null && SOURCE_TREND.equals(r.source))
                .sorted(Comparator.comparingDouble((Result r) -> r.score).reversed())
                .collect(Collectors.toList());
        List<Result> limitUpPassed = results.stream()
                .filter(r -> r.rejectReason == null && SOURCE_LIMIT_UP.equals(r.source))
                .sorted(Comparator.comparingDouble((Result r) -> r.score).reversed())
                .collect(Collectors.toList());
        List<Result> guidedPassed = results.stream()
                .filter(r -> r.rejectReason == null && SOURCE_LOCKUP_GUIDED.equals(r.source))
                .sorted(Comparator.comparingDouble((Result r) -> r.score).reversed())
                .collect(Collectors.toList());
        List<Result> picked = new ArrayList<>();
        picked.addAll(takeQuota(trendPassed, trendQuota));
        picked.addAll(takeQuota(limitUpPassed, limitUpQuota));
        picked.addAll(takeQuota(guidedPassed, lockupQuota));
        String funnel = formatRejects(rejects);
        log.info("过滤分布：{}", funnel);
        int hitCount = trendPassed.size() + limitUpPassed.size() + guidedPassed.size();
        log.info("命中：趋势 {} 只（取 {}）/ 涨停 {} 只（取 {}）/ 解禁引导 {} 只（取 {}），最终推荐 {} 只",
                trendPassed.size(), Math.min(trendQuota, trendPassed.size()),
                limitUpPassed.size(), Math.min(limitUpQuota, limitUpPassed.size()),
                guidedPassed.size(), Math.min(lockupQuota, guidedPassed.size()), picked.size());

        // 6. 全量落库（推荐 + 被挡），供"推荐组 vs 被挡组"对比；
        //    先落库再建分组，分组建失败时重跑会走 upsert，不丢实验数据
        Map<String, String> nameMap = loadNameMap(results.stream().map(r -> r.code).collect(Collectors.toList()));
        saveRecords(results, picked, endDate, prevDate, nameMap);
        saveRun(endDate, codes.size(), hitCount, picked.size(), funnel);

        // 7. 没有推荐时不建组、不发邮件（但候选快照照常落库）
        if (picked.isEmpty())
        {
            log.warn("无推荐股票，不生成分组");
            return;
        }

        // 8. 生成分组
        createGroup(picked, endDate, hitCount, prevDate);

        // 9. 邮件推送结果到手机
        notifyByEmail(picked, nameMap, endDate);

        log.info("========== 尾盘推荐完成 ==========");
    }

    /**
     * 按候选来源分发分析规则
     */
    private Result analyzeCandidate(StockRecommendPool candidate, LocalDate endDate, LocalDate prevDate,
                                    Map<String, Double> prevCloseMap,
                                    Map<String, StockDailyQuote> quoteMap,
                                    Map<String, DailyStat> dailyStatMap,
                                    Map<String, StockKline> limitUpDayBars,
                                    Map<String, Stock> stockMap,
                                    Map<String, AtomicInteger> rejects)
    {
        String code = candidate.getStockCode();
        if (SOURCE_LIMIT_UP.equals(candidate.getSource()))
        {
            LimitUpPullbackAnalyzer.PullbackResult pr = limitUpPullbackAnalyzer.analyze(
                    code, endDate, limitUpDayBars.get(code), stockMap.get(code), rejects);
            if (pr == null)
            {
                return null;
            }
            Result r = new Result();
            r.code = pr.code;
            r.source = SOURCE_LIMIT_UP;
            r.price = pr.price;
            r.openPct = 0;
            r.closePct = pr.pullback;
            r.closePos = pr.closePos;
            r.tailChange = pr.tailChange;
            r.turnoverRate = pr.turnover;
            r.gain5d = null;
            r.biasMa5 = null;
            r.volumeRatio = null;
            r.amount = 0;
            r.score = pr.score;
            r.rejectReason = null;
            return r;
        }

        Result r = analyze(code, endDate, prevCloseMap.get(code), quoteMap.get(code),
                dailyStatMap.get(code), rejects);
        if (r != null)
        {
            // TREND / LOCKUP_GUIDED 都走尾盘强度规则，保留来源标签
            r.source = candidate.getSource();
        }
        return r;
    }

    private int sourcePriority(String source)
    {
        if (SOURCE_LIMIT_UP.equals(source))
        {
            return 3;
        }
        if (SOURCE_LOCKUP_GUIDED.equals(source))
        {
            return 2;
        }
        if (SOURCE_TREND.equals(source))
        {
            return 1;
        }
        return 0;
    }

    private List<Result> takeQuota(List<Result> list, int quota)
    {
        if (list == null || list.isEmpty() || quota <= 0)
        {
            return new ArrayList<>();
        }
        if (list.size() <= quota)
        {
            return list;
        }
        return new ArrayList<>(list.subList(0, quota));
    }

    private Map<String, StockKline> loadLimitUpDayBars(List<String> codes, LocalDate prevDate)
    {
        Map<String, StockKline> map = new HashMap<>();
        if (codes == null || codes.isEmpty())
        {
            return map;
        }
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(
                codes, "D", prevDate.toString(), prevDate.toString());
        if (klines != null)
        {
            for (StockKline kline : klines)
            {
                if (kline.getStockCode() != null)
                {
                    map.put(kline.getStockCode(), kline);
                }
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
            for (Stock stock : stocks)
            {
                if (stock.getStockCode() != null)
                {
                    map.put(stock.getStockCode(), stock);
                }
            }
        }
        return map;
    }

    /**
     * 把推荐结果整理成邮件正文推送到手机
     */
    private void notifyByEmail(List<Result> results, Map<String, String> nameMap, LocalDate endDate)
    {
        StringBuilder sb = new StringBuilder();
        // 第一段：推荐股票名称（代码），方便直接复制导入同花顺
        sb.append("推荐股票 ").append(results.size()).append(" 只：\n");
        int i = 1;
        for (Result r : results)
        {
            sb.append(i++).append(". ")
                    .append(r.sourceLabel()).append(" ")
                    .append(nameMap.getOrDefault(r.code, "")).append("（").append(r.code).append("）\n");
        }
        sb.append("\n");
        // 第二段：明细指标（含"已经涨了多少"与"离5日线多远"，用于判断追高风险）
        sb.append("明细（涨幅/5日涨幅/MA5乖离/收盘位/尾盘30m/量比/换手）：\n");
        i = 1;
        for (Result r : results)
        {
            sb.append(i++).append(". ")
                    .append(r.sourceLabel()).append(" ")
                    .append(nameMap.getOrDefault(r.code, "")).append("(").append(r.code).append(")")
                    .append("  涨").append(String.format("%.2f", r.closePct)).append("%")
                    .append("  5日").append(r.gain5d == null ? "-" : String.format("%+.2f", r.gain5d)).append("%")
                    .append("  乖离").append(r.biasMa5 == null ? "-" : String.format("%+.2f", r.biasMa5)).append("%")
                    .append("  收盘位").append(String.format("%.2f", r.closePos))
                    .append("  尾盘").append(String.format("%+.2f", r.tailChange)).append("%")
                    .append(r.volumeRatio == null ? "" : "  量比" + String.format("%.2f", r.volumeRatio))
                    .append(r.turnoverRate == null ? "" : "  换手" + String.format("%.1f", r.turnoverRate) + "%")
                    .append("\n");
        }
        mailNotifyService.send(notifySubject + " " + endDate + "（" + results.size() + "只）", sb.toString());
    }

    private Map<String, String> loadNameMap(List<String> codes)
    {
        Map<String, String> nameMap = new HashMap<>();
        if (codes == null || codes.isEmpty())
        {
            return nameMap;
        }
        List<Map<String, Object>> nameRows = stockMapper.selectNamesByCodes(codes);
        if (nameRows != null)
        {
            for (Map<String, Object> row : nameRows)
            {
                Object code = row.get("stockCode");
                Object name = row.get("stockName");
                if (code != null && name != null)
                {
                    nameMap.put(code.toString(), name.toString());
                }
            }
        }
        return nameMap;
    }

    /**
     * 落库推荐快照：推荐与被挡的候选都记录，次日回填后可直接对比两组的实际表现
     */
    private void saveRecords(List<Result> analyzed, List<Result> picked, LocalDate endDate,
                             LocalDate prevDate, Map<String, String> nameMap)
    {
        if (analyzed.isEmpty())
        {
            return;
        }
        Map<String, Integer> rankMap = new HashMap<>();
        for (int i = 0; i < picked.size(); i++)
        {
            rankMap.put(picked.get(i).code, i + 1);
        }
        List<StockTailRecommendRecord> list = new ArrayList<>(analyzed.size());
        for (Result r : analyzed)
        {
            Integer rank = rankMap.get(r.code);
            StockTailRecommendRecord rec = new StockTailRecommendRecord();
            rec.setTradeDate(endDate.toString());
            rec.setBaseDate(prevDate.toString());
            rec.setRuleVersion(ruleVersion);
            rec.setStockCode(r.code);
            rec.setStockName(nameMap.get(r.code));
            rec.setSource(r.source);
            rec.setPicked(rank != null);
            rec.setRankNo(rank);
            rec.setRejectReason(r.rejectReason);
            rec.setRecPrice(BigDecimal.valueOf(r.price));
            rec.setOpenPct(r.openPct);
            rec.setClosePct(r.closePct);
            rec.setGain5d(r.gain5d);
            rec.setBiasMa5(r.biasMa5);
            rec.setClosePos(r.closePos);
            rec.setTailChange(r.tailChange);
            rec.setVolumeRatio(r.volumeRatio);
            rec.setTurnoverRate(r.turnoverRate);
            rec.setAmount(BigDecimal.valueOf(r.amount));
            rec.setScore(r.score);
            list.add(rec);
        }
        recommendRecordMapper.insertBatch(list);
        log.info("实验快照已落库 {} 条（推荐 {} 条，被挡 {} 条，ruleVersion={}）",
                list.size(), rankMap.size(), list.size() - rankMap.size(), ruleVersion);
    }

    /**
     * 落库本次运行的筛选漏斗，便于判断哪条闸门卡得最狠
     */
    private void saveRun(LocalDate endDate, int candidateCount, int hitCount, int pickedCount, String funnel)
    {
        StockTailRecommendRun run = new StockTailRecommendRun();
        run.setTradeDate(endDate.toString());
        run.setRuleVersion(ruleVersion);
        run.setCandidateCount(candidateCount);
        run.setHitCount(hitCount);
        run.setPickedCount(pickedCount);
        run.setRejectSummary(funnel != null && funnel.length() > 1000 ? funnel.substring(0, 1000) : funnel);
        recommendRunMapper.insertRun(run);
    }

    /**
     * 单只股票分析：抓当日分时K线，先排风险再判强度，命中返回 Result，否则计入过滤分布并返回 null
     */
    private Result analyze(String code, LocalDate endDate, Double prevClose, StockDailyQuote quote,
                           DailyStat dailyStat, Map<String, AtomicInteger> rejects)
    {
        try
        {
            TencentKlineDataSource.MinuteSnapshot snapshot = tencentKlineDataSource.fetchMinuteSnapshot(
                    code, inferMarket(code), minuteType, endDate.toString());
            List<StockKline> raw = snapshot.getBars();
            List<StockKline> bars = raw == null ? Collections.emptyList() : raw.stream()
                    .filter(k -> k.getTradeTime() != null && k.getClosePrice() != null
                            && k.getHighPrice() != null && k.getLowPrice() != null)
                    .sorted(Comparator.comparing(StockKline::getTradeTime))
                    .collect(Collectors.toList());
            // 分时数据不够（无法计算尾盘窗口）直接判数据不足，不再用 0 默认放行
            if (bars.size() < tailBars + 1)
            {
                return reject(rejects, "数据不足");
            }
            if (prevClose == null || prevClose <= 0)
            {
                return reject(rejects, "无昨收");
            }

            StockKline first = bars.get(0);
            StockKline last = bars.get(bars.size() - 1);
            double open = doubleOf(first.getOpenPrice());
            double close = doubleOf(last.getClosePrice());
            double dayHigh = -Double.MAX_VALUE;
            double dayLow = Double.MAX_VALUE;
            double amount = 0;
            for (StockKline k : bars)
            {
                dayHigh = Math.max(dayHigh, doubleOf(k.getHighPrice()));
                dayLow = Math.min(dayLow, doubleOf(k.getLowPrice()));
                // 分时成交量(股) × 收盘价 近似当日成交额(元)
                amount += doubleOf(k.getClosePrice()) * (k.getVolume() == null ? 0L : k.getVolume());
            }
            if (!(dayHigh > dayLow))
            {
                return reject(rejects, "数据不足");
            }

            double openPct = (open - prevClose) / prevClose * 100;
            double closePct = (close - prevClose) / prevClose * 100;
            double closePos = (close - dayLow) / (dayHigh - dayLow);

            double tailStart = doubleOf(bars.get(bars.size() - tailBars - 1).getClosePrice());
            if (tailStart <= 0)
            {
                return reject(rejects, "数据不足");
            }
            double tailChange = (close - tailStart) / tailStart * 100;

            // 以下指标在淘汰时也保留，用于事后对比"推荐组 vs 被挡组"
            // 换手率/量比优先取分时接口当次的实时快照（14:45 调用即 14:45 的值），
            // 取不到再退回当日 stock_daily_quote 快照（收盘后才落库，仅作兜底）
            Double volumeRatio = snapshot.getVolumeRatio();
            Double turnoverRate = snapshot.getTurnoverRate();
            if (volumeRatio == null && quote != null)
            {
                volumeRatio = toDouble(quote.getVolumeRatio());
            }
            if (turnoverRate == null && quote != null)
            {
                turnoverRate = toDouble(quote.getTurnoverRate());
            }
            Double gain5d = dailyStat == null ? null : dailyStat.gain5d;
            Double biasMa5 = null;
            if (dailyStat != null)
            {
                double ma5 = dailyStat.ma5With(close);
                biasMa5 = ma5 > 0 ? (close - ma5) / ma5 * 100 : 0d;
            }

            Result r = new Result();
            r.code = code;
            r.price = close;
            r.openPct = openPct;
            r.closePct = closePct;
            r.closePos = closePos;
            r.tailChange = tailChange;
            r.volumeRatio = volumeRatio;
            r.turnoverRate = turnoverRate;
            r.gain5d = gain5d;
            r.biasMa5 = biasMa5;
            r.amount = amount;
            r.score = score(closePos, closePct, tailChange, biasMa5 == null ? maxBiasMa5 : biasMa5);
            r.rejectReason = firstRejectReason(r, rejects);
            return r;
        }
        catch (Exception e)
        {
            log.debug("分析失败 code={}, error={}", code, e.getMessage());
            return reject(rejects, "抓取失败");
        }
    }

    /**
     * 综合评分：越强、尾盘越稳、离5日线越近、涨幅越接近理想值，得分越高
     */
    private double score(double closePos, double closePct, double tailChange, double biasMa5)
    {
        double tailSpan = 2.0 - minTailChange;
        double tailScore = tailSpan > 0 ? clamp01((tailChange - minTailChange) / tailSpan) : 0;
        double biasScore = maxBiasMa5 > 0 ? clamp01(1 - biasMa5 / maxBiasMa5) : 0;
        double gainSpan = Math.max(1.0, maxCloseGain - idealGain);
        double gainScore = clamp01(1 - Math.abs(closePct - idealGain) / gainSpan);
        return 0.40 * closePos + 0.25 * tailScore + 0.20 * biasScore + 0.15 * gainScore;
    }

    private double clamp01(double v)
    {
        return Math.max(0, Math.min(1, v));
    }

    private Result reject(Map<String, AtomicInteger> rejects, String reason)
    {
        rejects.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        return null;
    }

    /**
     * 按顺序判定全部过滤条件，返回第一条不满足的原因（null = 全部通过），并累加漏斗计数。
     * 注意：指标在淘汰时也保留在 Result 上，用于事后对比"推荐组 vs 被挡组"的次日表现。
     */
    private String firstRejectReason(Result r, Map<String, AtomicInteger> rejects)
    {
        String reason = null;
        if (r.closePct >= maxGain)
        {
            reason = "涨停买不进";
        }
        else if (r.closePct > maxCloseGain)
        {
            reason = "涨幅过高";
        }
        else if (r.closePct < minGain)
        {
            reason = "涨幅不足";
        }
        else if (r.openPct > maxOpenGain)
        {
            reason = "高开追高";
        }
        else if (r.amount < minTurnoverAmount * YI)
        {
            reason = "成交额不足";
        }
        else if (r.volumeRatio != null && r.volumeRatio < minVolumeRatio)
        {
            reason = "量比不足";
        }
        else if (r.volumeRatio != null && maxVolumeRatio > 0 && r.volumeRatio > maxVolumeRatio)
        {
            reason = "过度放量";
        }
        else if (r.turnoverRate != null && r.turnoverRate < minTurnoverRate)
        {
            reason = "换手不足";
        }
        else if (r.turnoverRate != null && maxTurnoverRate > 0 && r.turnoverRate > maxTurnoverRate)
        {
            reason = "换手过高";
        }
        else if (r.gain5d == null || r.biasMa5 == null)
        {
            reason = "历史不足";
        }
        else if (r.gain5d > maxGain5d)
        {
            reason = "近5日已涨透";
        }
        else if (r.biasMa5 > maxBiasMa5)
        {
            reason = "偏离5日线过远";
        }
        else if (r.closePos < minClosePos)
        {
            reason = "收盘位不足";
        }
        else if (r.tailChange < minTailChange)
        {
            reason = "尾盘跳水";
        }
        if (reason != null)
        {
            rejects.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        }
        return reason;
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

    private void createGroup(List<Result> picked, LocalDate endDate, int hitCount, LocalDate prevDate)
    {
        String dailyGroupName = groupName + "_" + endDate;
        String[] codes = picked.stream().map(r -> r.code).toArray(String[]::new);
        long trendPicked = picked.stream().filter(r -> SOURCE_TREND.equals(r.source)).count();
        long limitUpPicked = picked.stream().filter(r -> SOURCE_LIMIT_UP.equals(r.source)).count();
        long guidedPicked = picked.stream().filter(r -> SOURCE_LOCKUP_GUIDED.equals(r.source)).count();
        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(codes);
        group.setCreateBy("system");
        group.setRemark(String.format(
                "基准日%s；趋势%d只+涨停%d只+解禁引导%d只；趋势规则：涨幅%.1f~%.1f%%+高开≤%.1f%%+成交额≥%.1f亿+5日涨幅≤%.0f%%+乖离≤%.0f%%；涨停规则：回调承接；解禁引导：温和多头+低振幅；命中%d",
                prevDate, trendPicked, limitUpPicked, guidedPicked, minGain, maxCloseGain, maxOpenGain,
                minTurnoverAmount, maxGain5d, maxBiasMa5, hitCount));
        stockGroupService.insertGroup(group);

        log.info("生成尾盘推荐分组 [{}]，推荐 {} 只（趋势 {} / 涨停 {} / 解禁引导 {}，命中 {} 只）",
                dailyGroupName, picked.size(), trendPicked, limitUpPicked, guidedPicked, hitCount);
        for (Result r : picked)
        {
            log.info("    [{}] {} 涨{}% 5日{}% 乖离{}% 收盘位{} 尾盘{}% 量比{} 换手{}% 评分{}",
                    r.source, r.code, fmt(r.closePct), fmtOrDash(r.gain5d), fmtOrDash(r.biasMa5),
                    fmt(r.closePos), fmt(r.tailChange), r.volumeRatio == null ? "-" : fmt(r.volumeRatio),
                    r.turnoverRate == null ? "-" : fmt(r.turnoverRate), fmt(r.score));
        }
    }

    private String fmt(double v)
    {
        return String.format("%.2f", v);
    }

    private String fmtOrDash(Double v)
    {
        return v == null ? "-" : String.format("%.2f", v);
    }

    private Map<String, Double> loadPrevClose(List<String> codes, LocalDate prevDate)
    {
        Map<String, Double> map = new HashMap<>();
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", prevDate.toString(), prevDate.toString());
        if (klines != null)
        {
            for (StockKline k : klines)
            {
                if (k.getStockCode() != null && k.getClosePrice() != null)
                {
                    map.put(k.getStockCode(), k.getClosePrice().doubleValue());
                }
            }
        }
        return map;
    }

    /**
     * 预加载当日行情快照（量比 / 换手率）。快照未同步时返回空 Map，对应条件不生效。
     */
    private Map<String, StockDailyQuote> loadQuote(LocalDate endDate)
    {
        Map<String, StockDailyQuote> map = new HashMap<>();
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectByTradeDates(Collections.singletonList(Date.valueOf(endDate)));
        if (quotes != null)
        {
            for (StockDailyQuote q : quotes)
            {
                if (q.getStockCode() != null)
                {
                    map.putIfAbsent(q.getStockCode(), q);
                }
            }
        }
        return map;
    }

    /**
     * 预加载截至昨日的日K，计算近5日累计涨幅与 MA5 所需的最近4个收盘价。
     * 不足6根日K的股票不放入结果，调用方按"历史不足"淘汰。
     */
    private Map<String, DailyStat> loadDailyStats(List<String> codes, LocalDate prevDate)
    {
        Map<String, DailyStat> map = new HashMap<>();
        Map<String, List<StockKline>> byCode = new HashMap<>();
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D",
                prevDate.minusDays(20).toString(), prevDate.toString());
        if (klines == null)
        {
            return map;
        }
        for (StockKline k : klines)
        {
            if (k.getStockCode() != null && k.getClosePrice() != null && k.getTradeTime() != null)
            {
                byCode.computeIfAbsent(k.getStockCode(), c -> new ArrayList<>()).add(k);
            }
        }
        for (Map.Entry<String, List<StockKline>> e : byCode.entrySet())
        {
            List<StockKline> list = e.getValue();
            list.sort(Comparator.comparing(StockKline::getTradeTime));
            int n = list.size();
            if (n < 6)
            {
                continue;
            }
            double last = list.get(n - 1).getClosePrice().doubleValue();
            double base = list.get(n - 6).getClosePrice().doubleValue();
            if (base <= 0)
            {
                continue;
            }
            DailyStat st = new DailyStat();
            st.gain5d = (last - base) / base * 100;
            st.last4 = new double[]{
                    list.get(n - 4).getClosePrice().doubleValue(),
                    list.get(n - 3).getClosePrice().doubleValue(),
                    list.get(n - 2).getClosePrice().doubleValue(),
                    last
            };
            map.put(e.getKey(), st);
        }
        return map;
    }

    private String inferMarket(String code)
    {
        return code.startsWith("6") ? "SH" : "SZ";
    }

    private double doubleOf(BigDecimal v)
    {
        return v == null ? 0d : v.doubleValue();
    }

    private Double toDouble(BigDecimal v)
    {
        return v == null ? null : v.doubleValue();
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

    /** 截至昨日的日K统计：近5日累计涨幅 + 最近4个收盘价（用于含今日现价的 MA5） */
    private static class DailyStat
    {
        private double gain5d;
        private double[] last4;

        double ma5With(double today)
        {
            if (last4 == null || last4.length < 4)
            {
                return 0;
            }
            double sum = today;
            for (double c : last4)
            {
                sum += c;
            }
            return sum / 5.0;
        }
    }

    private static class Result
    {
        private String code;
        /** 来源：TREND / LIMIT_UP */
        private String source;
        private double price;
        private double openPct;
        private double closePct;
        private double closePos;
        private double tailChange;
        private Double volumeRatio;
        private Double turnoverRate;
        private Double gain5d;
        private Double biasMa5;
        private double amount;
        private double score;
        /** 淘汰原因：null 表示通过全部过滤 */
        private String rejectReason;

        private String sourceLabel()
        {
            if (SOURCE_LIMIT_UP.equals(source))
            {
                return "[涨停]";
            }
            if (SOURCE_LOCKUP_GUIDED.equals(source))
            {
                return "[解禁]";
            }
            return "[趋势]";
        }
    }
}
