package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockRisingState;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockRisingStateMapper;
import com.ruoyi.system.service.IStockDayTradingService;
import com.ruoyi.system.service.IStockGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 趋势积累池日推荐实现
 *
 * 一、按波段状态拆分日推荐：「趋势上涨选股」任务每日全量覆盖积累分组（含 ATR 波段删除），
 *     并把每只池内股票的波段状态写入 stock_rising_state 表；本任务按状态拆成两个分组：
 *     1. 上升组：波段状态=上升（当前创新高、动量最强）；
 *     2. 震荡组：波段状态=震荡（浅回撤 ≤1ATR，上升趋势中的小幅回调）；
 *     3. 回调股（1~2ATR 回撤）不进日推荐；换手率不达标的不进日推荐（门槛按流通市值分档）。
 *     通过过滤的股票按评分（阶段位置 + 量能 + 换手适中 + 涨幅适中）降序，每组只取前 topN 只，
 *     日志同时输出每只的止损价（阶段低点与 2ATR 回撤位取高者）。
 *     分组名 = 积累池分组名 + "_" + 日期 + "_" + 状态。
 *
 *     市场环境过滤：当日上涨家数占比低于 min-up-ratio 时，直接不生成日推荐（趋势跟踪在普跌日胜率低）。
 *
 * 二、涨停首二板分组：遍历当日涨停记录，过滤出首板/二板（up_num 为空=首板，
 *     「N天2板」=二板），按封板时间升序取前 limit-up-top-n 只，生成「涨停首二板_日期」分组。
 *
 * @author ruoyi
 */
@Service
public class StockDayTradingServiceImpl implements IStockDayTradingService
{
    private static final Logger log = LoggerFactory.getLogger(StockDayTradingServiceImpl.class);

    /** 匹配 up_num 形如「N天M板」末尾的板数 */
    private static final Pattern BOARD_PATTERN = Pattern.compile("(\\d+)板$");

    /** 日推荐候选（评分 + 止损位） */
    private static class ScoredStock
    {
        private String code;
        private double score;
        private Double stopPrice;
        private double close;
    }

    /** 涨停首二板候选（封板时间用于排序） */
    private static class LimitUpPick
    {
        private String code;
        private String sealTime;
        private int board;
    }

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockRisingStateMapper stockRisingStateMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    /** 目标趋势积累分组名（与趋势上涨选股任务保持一致） */
    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String targetGroupName;

    /** 日推荐流动性过滤：换手率下限(%) */
    @Value("${stock.day-trading.min-turnover-rate:3.0}")
    private double minTurnoverRate;

    /** 每组日推荐最多输出数量（按评分取前N，<=0 表示不限） */
    @Value("${stock.day-trading.top-n:15}")
    private int topN;

    /** 市场环境过滤开关：上涨家数占比低于阈值时不生成日推荐 */
    @Value("${stock.day-trading.market-filter-enabled:false}")
    private boolean marketFilterEnabled;

    /** 是否生成「震荡」日推荐分组（默认关闭，只推上升组） */
    @Value("${stock.day-trading.shake-group-enabled:false}")
    private boolean shakeGroupEnabled;

    /** 近5日累计涨幅上限(%)：超过视为已经涨透 */
    @Value("${stock.day-trading.max-gain-5d:15.0}")
    private double maxGain5d;

    /** 相对 MA20 乖离上限(%)：超过视为过度偏离均线 */
    @Value("${stock.day-trading.max-bias-ma20:12.0}")
    private double maxBiasMa20;

    /** 市场环境阈值：上涨家数占比下限（0~1） */
    @Value("${stock.day-trading.min-up-ratio:0.30}")
    private double minUpRatio;

    /** 换手率门槛分档：流通市值 < smallCapYi 亿用小盘门槛 */
    @Value("${stock.day-trading.small-cap-turnover-rate:5.0}")
    private double smallCapTurnoverRate;

    /** 换手率门槛分档：流通市值 > largeCapYi 亿用大盘门槛 */
    @Value("${stock.day-trading.large-cap-turnover-rate:1.5}")
    private double largeCapTurnoverRate;

    /** 小盘市值上限（亿元） */
    @Value("${stock.day-trading.small-cap-yi:50}")
    private double smallCapYi;

    /** 大盘市值下限（亿元） */
    @Value("${stock.day-trading.large-cap-yi:200}")
    private double largeCapYi;

    /** 首二板最多输出数量（按封板时间取前N，<=0 表示不限） */
    @Value("${stock.day-trading.limit-up-top-n:15}")
    private int limitUpTopN;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    /** 涨停首二板分组名（固定名，不含日期） */
    @Value("${stock.day-trading.limit-up-group-name:涨停首二板}")
    private String limitUpGroupName;

    /** 是否排除 ST/*ST 股票（与趋势上涨选股保持一致） */
    @Value("${stock.rising-trend.exclude-st:true}")
    private boolean excludeSt;

    /** 排除的板块代码前缀（逗号分隔），默认排除创业板(300/301)、科创板(688/689) */
    @Value("${stock.rising-trend.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    @Override
    public void execute(String date)
    {
        LocalDate endDate = parseDate(date);
        log.info("========== 趋势积累池日推荐开始 ==========");
        log.info("参数：targetGroupName={} endDate={} minTurnoverRate={} topN={} limitUpGroupName={}",
                targetGroupName, endDate, minTurnoverRate, topN, limitUpGroupName);

        // 一、按波段状态拆分「上升」「震荡」两个日推荐分组
        generateBandGroups(endDate);

        // 二、遍历当日涨停，过滤首板/二板生成一个分组
        generateLimitUpGroup(endDate);

        log.info("========== 趋势积累池日推荐完成 ==========");
    }

    /**
     * 按波段状态拆分「上升」「震荡」两个日推荐分组
     */
    private void generateBandGroups(LocalDate endDate)
    {
        // 0. 市场环境过滤：普跌日不出日推荐（趋势跟踪在弱市里胜率低）
        if (marketFilterEnabled)
        {
            double ratio = marketUpRatio(endDate);
            if (ratio < 0)
            {
                log.warn("无 {} 行情快照，跳过市场环境过滤", endDate);
            }
            else if (ratio < minUpRatio)
            {
                log.warn("市场环境过滤：上涨家数占比 {}% < {}%，今日不生成日推荐",
                        String.format("%.1f", ratio * 100), String.format("%.0f%%", minUpRatio * 100));
                return;
            }
            else
            {
                log.info("市场环境：上涨家数占比 {}%（阈值 {}%），通过",
                        String.format("%.1f", ratio * 100), String.format("%.0f", minUpRatio * 100));
            }
        }

        // 1. 查找趋势积累分组
        StockGroup group = findGroupByName(targetGroupName);
        if (group == null)
        {
            log.warn("未找到目标分组 [{}]，跳过波段状态日推荐", targetGroupName);
            return;
        }

        // 2. 取分组内现有股票
        List<Stock> poolStocks = stockGroupService.selectStocksByGroupId(group.getId());
        if (poolStocks == null || poolStocks.isEmpty())
        {
            log.warn("分组 [{}] 内无股票，跳过波段状态日推荐", targetGroupName);
            return;
        }
        Map<String, Stock> stockMap = new HashMap<>();
        for (Stock s : poolStocks)
        {
            if (s.getStockCode() != null)
            {
                stockMap.put(s.getStockCode(), s);
            }
        }
        List<String> codes = new ArrayList<>(stockMap.keySet());
        log.info("分组 [{}] 当前 {} 只，开始按波段状态拆分日推荐", targetGroupName, codes.size());

        // 3. 查询每只股票的波段状态（含阶段高低点与止损价）
        Map<String, StockRisingState> stateMap = new HashMap<>();
        List<StockRisingState> states = stockRisingStateMapper.selectByStockCodes(codes);
        if (states != null)
        {
            for (StockRisingState st : states)
            {
                if (st.getStockCode() != null && st.getStatus() != null)
                {
                    stateMap.put(st.getStockCode(), st);
                }
            }
        }
        log.info("有波段状态的股票 {} 只", stateMap.size());

        // 4. 逐只判定：状态归组 + 换手率过滤 + 抗追高 + 评分
        Map<String, double[]> dailyStatMap = loadDailyStat(codes, endDate);
        Map<String, Integer> rejects = new LinkedHashMap<>();
        List<ScoredStock> upList = new ArrayList<>();
        List<ScoredStock> shakeList = new ArrayList<>();
        for (String code : codes)
        {
            StockRisingState st = stateMap.get(code);
            if (st == null)
            {
                continue;
            }
            String status = st.getStatus();
            // 只推「上升」「震荡」，回调股不进日推荐
            if (!"上升".equals(status) && !"震荡".equals(status))
            {
                continue;
            }
            Stock stock = stockMap.get(code);
            // 流动性过滤：换手率不足的不进日推荐（但保留在积累池）
            if (isLowLiquidity(stock))
            {
                continue;
            }
            // 抗追高：近5日涨幅过大、MA20 乖离过大的不进日推荐
            double[] stat = dailyStatMap.get(code);
            if (stat != null)
            {
                if (stat[0] > maxGain5d)
                {
                    rejects.merge("近5日涨幅过大", 1, Integer::sum);
                    continue;
                }
                if (stat[1] > maxBiasMa20)
                {
                    rejects.merge("偏离MA20过远", 1, Integer::sum);
                    continue;
                }
            }
            ScoredStock item = new ScoredStock();
            item.code = code;
            item.score = score(stock, st);
            item.stopPrice = st.getStopPrice();
            item.close = stock.getCurrentPrice() == null ? 0 : stock.getCurrentPrice().doubleValue();
            if ("上升".equals(status))
            {
                upList.add(item);
            }
            else
            {
                shakeList.add(item);
            }
        }
        log.info("筛选结果：上升 {} 只，震荡 {} 只（换手率≥{}%，抗追高剔除：{}）",
                upList.size(), shakeList.size(), minTurnoverRate, rejects);

        // 5. 按评分降序取前 topN，生成日推荐分组（震荡组默认关闭）
        List<ScoredStock> topUp = takeTop(upList);
        createDailyGroup(topUp, upList.size(), endDate, "上升");
        if (shakeGroupEnabled)
        {
            List<ScoredStock> topShake = takeTop(shakeList);
            createDailyGroup(topShake, shakeList.size(), endDate, "震荡");
        }
        else
        {
            log.info("震荡组已关闭（shake-group-enabled=false），仅输出上升组");
        }
    }

    /**
     * 日推荐评分（0~1）：阶段位置 55% + 量能 20% + 换手适中 15% + 当日涨幅适中 10%
     *
     * 用日线数据（而非分时），目的是把「离阶段高点最近、量能温和放大、换手不过热、涨幅不追高」的票排前面，
     * 让每组只输出可执行的 topN 而不是全量。
     */
    private double score(Stock stock, StockRisingState st)
    {
        double price = stock.getCurrentPrice() == null ? 0 : stock.getCurrentPrice().doubleValue();
        double high = st.getHighPrice() == null ? 0 : st.getHighPrice();
        double low = st.getLowPrice() == null ? 0 : st.getLowPrice();
        double pos = (high > low && price > 0) ? clamp01((price - low) / (high - low)) : 0;

        double volumeRatio = stock.getVolumeRatio() == null ? 0 : stock.getVolumeRatio().doubleValue();
        double vol = clamp01(volumeRatio / 2.0);

        double turnover = stock.getTurnoverRate() == null ? 0 : stock.getTurnoverRate().doubleValue();
        double turn = clamp01(1 - Math.abs(turnover - 6.0) / 10.0);

        double change = stock.getChangeRate() == null ? 0 : stock.getChangeRate().doubleValue();
        double chg = clamp01(1 - Math.abs(change - 3.0) / 5.0);

        return 0.55 * pos + 0.20 * vol + 0.15 * turn + 0.10 * chg;
    }

    private double clamp01(double v)
    {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * 按评分降序取前 topN（topN<=0 表示不限），入参列表不会被修改
     */
    private List<ScoredStock> takeTop(List<ScoredStock> list)
    {
        if (list == null || list.isEmpty())
        {
            return new ArrayList<>();
        }
        List<ScoredStock> sorted = new ArrayList<>(list);
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        if (topN > 0 && sorted.size() > topN)
        {
            return new ArrayList<>(sorted.subList(0, topN));
        }
        return sorted;
    }

    /**
     * 遍历当日涨停记录，过滤首板/二板，按封板时间取前 limitUpTopN 只生成分组。
     *
     * 封板时间（stock_limit_up_record.time，如 "2026-10-30 09:35:00"）越早说明买盘越强，
     * 同一只票跨板块出现时取最早封板时间。
     */
    private void generateLimitUpGroup(LocalDate endDate)
    {
        List<StockLimitUpRecord> records = stockLimitUpRecordMapper.selectByTradeDate(endDate.toString());
        if (records == null || records.isEmpty())
        {
            log.warn("当日 [{}] 无涨停记录，跳过涨停首二板分组", endDate);
            return;
        }

        Map<String, LimitUpPick> pickMap = new LinkedHashMap<>();
        List<String> excludePrefixes = parseExcludePrefixes();
        for (StockLimitUpRecord r : records)
        {
            if (r.getSecuCode() == null || r.getSecuCode().isEmpty())
            {
                continue;
            }
            // 排除 ST/*ST
            if (excludeSt && r.getSecuName() != null && r.getSecuName().toUpperCase().contains("ST"))
            {
                continue;
            }
            // 排除创业板/科创板
            if (startsWithAny(r.getSecuCode(), excludePrefixes))
            {
                continue;
            }
            int board = boardOf(r.getUpNum());
            if (board != 1 && board != 2)
            {
                continue;
            }
            String sealTime = sealTimeOf(r.getTime());
            LimitUpPick exist = pickMap.get(r.getSecuCode());
            if (exist == null)
            {
                LimitUpPick pick = new LimitUpPick();
                pick.code = r.getSecuCode();
                pick.sealTime = sealTime;
                pick.board = board;
                pickMap.put(r.getSecuCode(), pick);
            }
            else if (sealTime.compareTo(exist.sealTime) < 0)
            {
                exist.sealTime = sealTime;
            }
        }
        int first = (int) pickMap.values().stream().filter(p -> p.board == 1).count();
        int second = (int) pickMap.values().stream().filter(p -> p.board == 2).count();
        log.info("当日涨停 {} 条，去重后首板 {} 只、二板 {} 只", records.size(), first, second);

        List<LimitUpPick> picks = new ArrayList<>(pickMap.values());
        if (picks.isEmpty())
        {
            log.warn("无首板/二板股票，跳过涨停首二板分组");
            return;
        }
        picks.sort(Comparator.comparing(p -> p.sealTime));
        int hitCount = picks.size();
        if (limitUpTopN > 0 && picks.size() > limitUpTopN)
        {
            picks = new ArrayList<>(picks.subList(0, limitUpTopN));
        }

        String dailyGroupName = limitUpGroupName + "_" + endDate;
        // 幂等：同名分组已存在则跳过
        if (groupExists(dailyGroupName))
        {
            log.info("分组 [{}] 已存在，跳过", dailyGroupName);
            return;
        }

        String[] codes = picks.stream().map(p -> p.code).toArray(String[]::new);
        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(codes);
        group.setCreateBy("system");
        group.setRemark(String.format("%s（截止%s，当日涨停首板/二板，首板%d只/二板%d只，按封板时间取前%d只；封板越早越强）",
                dailyGroupName, endDate, first, second, picks.size()));
        stockGroupService.insertGroup(group);

        log.info("生成分组 [{}]，命中 {} 只，按封板时间取前 {} 只", dailyGroupName, hitCount, picks.size());
        int rank = 1;
        for (LimitUpPick p : picks)
        {
            log.info("    {}. {} 封板{} {}板", rank++, p.code, p.sealTime, p.board);
        }
    }

    /**
     * 从涨停时间（形如 "2026-10-30 09:35:00"）解析出 HH:mm，缺失返回 99:99 以便排在最后
     */
    private String sealTimeOf(String time)
    {
        if (time == null)
        {
            return "99:99";
        }
        String t = time.trim();
        if (t.length() >= 16 && t.charAt(10) == ' ')
        {
            return t.substring(11, 16);
        }
        if (t.length() >= 5 && t.charAt(2) == ':')
        {
            return t.substring(0, 5);
        }
        return "99:99";
    }

    /**
     * 解析 up_num 得到连板数：空=首板(1)；「N天M板」取末尾 M
     */
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

    /**
     * 解析排除板块代码前缀配置
     */
    private List<String> parseExcludePrefixes()
    {
        if (excludeCodePrefixes == null || excludeCodePrefixes.trim().isEmpty())
        {
            return Arrays.asList("300", "301", "688", "689");
        }
        return Arrays.stream(excludeCodePrefixes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 判断股票代码是否命中任一排除前缀
     */
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

    /**
     * 生成当天日推荐分组（分组名含日期与状态，幂等：同名已存在则跳过）
     *
     * @param picks    按评分降序取前 topN 的股票
     * @param hitCount 通过状态 + 换手率过滤的总数（备注里说明截断前数量）
     */
    private void createDailyGroup(List<ScoredStock> picks, int hitCount, LocalDate endDate, String status)
    {
        if (picks == null || picks.isEmpty())
        {
            log.warn("无「{}」状态股票，跳过生成该日推荐分组", status);
            return;
        }

        String dailyGroupName = targetGroupName + "_" + endDate + "_" + status;

        // 幂等：同名分组已存在则跳过
        if (groupExists(dailyGroupName))
        {
            log.info("日推荐分组 [{}] 已存在，跳过", dailyGroupName);
            return;
        }

        String[] codes = picks.stream().map(p -> p.code).toArray(String[]::new);
        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(codes);
        group.setCreateBy("system");
        group.setRemark(String.format("%s（截止%s自积累池[%s]筛选，波段状态=%s且换手率≥%.1f%%，命中%d只取评分前%d只；评分=阶段位置55%%+量能20%%+换手15%%+涨幅10%%，止损=阶段低点与2ATR取高）",
                dailyGroupName, endDate, targetGroupName, status, minTurnoverRate, hitCount, picks.size()));
        stockGroupService.insertGroup(group);

        log.info("生成日推荐分组 [{}]，命中 {} 只，按评分取前 {} 只", dailyGroupName, hitCount, picks.size());
        int rank = 1;
        for (ScoredStock p : picks)
        {
            log.info("    {}. {} 评分{} 现价{} 止损{}",
                    rank++, p.code, String.format("%.3f", p.score), String.format("%.2f", p.close),
                    p.stopPrice == null ? "-" : String.format("%.2f", p.stopPrice));
        }
    }

    /**
     * 按精确名称查找分组，找不到返回 null
     */
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

    /**
     * 同名分组是否已存在
     */
    private boolean groupExists(String name)
    {
        return findGroupByName(name) != null;
    }

    /**
     * 流动性过滤：换手率不足则视为低流动性，不进当日推荐（但保留在积累池）
     */
    /**
     * 预加载近5日累计涨幅与 MA20 乖离（用于抗追高过滤）
     *
     * @return code -> [gain5d%, biasMa20%]
     */
    private Map<String, double[]> loadDailyStat(List<String> codes, LocalDate endDate)
    {
        Map<String, double[]> map = new HashMap<>();
        if (codes == null || codes.isEmpty())
        {
            return map;
        }
        List<StockKline> klines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D",
                endDate.minusDays(40).toString(), endDate.toString());
        if (klines == null)
        {
            return map;
        }
        Map<String, List<StockKline>> byCode = new HashMap<>();
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
            double base5 = list.get(n - 6).getClosePrice().doubleValue();
            if (base5 <= 0)
            {
                continue;
            }
            double gain5d = (last - base5) / base5 * 100;
            int from = Math.max(0, n - 20);
            double sum = 0;
            int cnt = 0;
            for (int i = from; i < n; i++)
            {
                sum += list.get(i).getClosePrice().doubleValue();
                cnt++;
            }
            double ma20 = cnt > 0 ? sum / cnt : 0;
            double bias20 = ma20 > 0 ? (last - ma20) / ma20 * 100 : 0;
            map.put(e.getKey(), new double[]{gain5d, bias20});
        }
        return map;
    }

    private boolean isLowLiquidity(Stock stock)
    {
        if (stock == null)
        {
            return true;
        }
        if (stock.getTurnoverRate() == null)
        {
            return false;
        }
        return stock.getTurnoverRate().doubleValue() < turnoverThreshold(stock);
    }

    /**
     * 换手率门槛按流通市值分档：小盘要求更高（否则买不进卖不出），大盘可以更低（盘子大、换手天然低）
     */
    private double turnoverThreshold(Stock stock)
    {
        BigDecimal cap = stock.getCircMarketCap();
        if (cap == null || cap.doubleValue() <= 0)
        {
            return minTurnoverRate;
        }
        double capYi = cap.doubleValue() / 100000000.0;
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

    /**
     * 当日上涨家数占比（0~1），无行情快照返回 -1
     */
    private double marketUpRatio(LocalDate endDate)
    {
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectByTradeDates(
                Collections.singletonList(Date.valueOf(endDate)));
        if (quotes == null || quotes.isEmpty())
        {
            return -1;
        }
        int total = 0;
        int up = 0;
        for (StockDailyQuote q : quotes)
        {
            if (q.getChangeRate() == null)
            {
                continue;
            }
            total++;
            if (q.getChangeRate().doubleValue() > 0)
            {
                up++;
            }
        }
        return total == 0 ? -1 : (double) up / total;
    }

    /**
     * 解析截止日期：入参为空或解析失败时默认当天
     */
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
