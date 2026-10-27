package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockRisingState;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockRisingStateMapper;
import com.ruoyi.system.service.IMailNotifyService;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockTailRecommendService;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 尾盘推荐实现
 *
 * 每个交易日下午 14:45 运行（收盘前），基于昨日分组（上升/震荡/涨停首二板）的股票，
 * 拉取当日分时（5分钟）K线，按「尾盘可买」口径筛选：
 *   1. 未涨停（当日涨幅 < maxGain%）——涨停封死的尾盘买不进；
 *   2. 当日上涨（涨幅 >= minGain%）；
 *   3. 收盘贴近阶段高点（收盘位置 >= minClosePos）——全天资金持续买入、没被砸；
 *   4. 尾盘不跳水（尾盘30分钟涨跌 >= minTailChange%）；
 *   5. 量比达标（volumeRatio >= minVolumeRatio，若当日快照缺失则跳过该过滤）。
 * 命中股票生成「尾盘推荐_日期」分组，供用户在 15:00 收盘前买入。
 *
 * @author ruoyi
 */
@Service
public class StockTailRecommendServiceImpl implements IStockTailRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockTailRecommendServiceImpl.class);

    private static final Pattern BOARD_PATTERN = Pattern.compile("(\\d+)板$");

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockRisingStateMapper stockRisingStateMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private IMailNotifyService mailNotifyService;

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

    /** 当日涨幅下限(%)：至少小幅上涨 */
    @Value("${stock.tail-recommend.min-gain:0.5}")
    private double minGain;

    /** 收盘位置下限(0~1)：收盘价相对当日[最低,最高]区间的比例 */
    @Value("${stock.tail-recommend.min-close-pos:0.7}")
    private double minClosePos;

    /** 尾盘30分钟涨跌下限(%)：负值允许小幅回落，过滤真正的尾盘跳水 */
    @Value("${stock.tail-recommend.min-tail-change:-0.5}")
    private double minTailChange;

    /** 量比下限：放量确认 */
    @Value("${stock.tail-recommend.min-volume-ratio:1.2}")
    private double minVolumeRatio;

    /** 并行抓取分时K线的线程数 */
    @Value("${stock.tail-recommend.thread-pool-size:16}")
    private int threadPoolSize;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.rising-trend.exclude-st:true}")
    private boolean excludeSt;

    /** 排除的板块代码前缀 */
    @Value("${stock.rising-trend.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    /** 尾盘30分钟对应的5分钟bar数量（默认6根） */
    @Value("${stock.tail-recommend.tail-bars:6}")
    private int tailBars;

    @Override
    public void execute(String date)
    {
        LocalDate endDate = parseDate(date);
        log.info("========== 尾盘推荐开始 ==========");
        log.info("参数：endDate={} minuteType={} maxGain={} minGain={} minClosePos={} minTailChange={} minVolumeRatio={}",
                endDate, minuteType, maxGain, minGain, minClosePos, minTailChange, minVolumeRatio);

        // 1. 确定前一交易日
        String prevDateStr = stockKlineMapper.selectMaxTradeDateBefore(endDate.toString());
        if (prevDateStr == null)
        {
            log.warn("未找到前一交易日，跳过");
            return;
        }
        LocalDate prevDate = LocalDate.parse(prevDateStr);
        log.info("前一交易日 = {}", prevDate);

        // 2. 收集候选股票（昨日上升/震荡 + 昨日涨停首二板）
        Map<String, String> candidateMap = new LinkedHashMap<>();
        collectRisingShake(candidateMap);
        collectLimitUp(candidateMap, prevDate);
        if (candidateMap.isEmpty())
        {
            log.warn("无候选股票，跳过");
            return;
        }
        List<String> codes = new ArrayList<>(candidateMap.keySet());
        log.info("候选股票 {} 只", codes.size());

        // 3. 预加载昨收 + 今日量比
        Map<String, Double> prevCloseMap = loadPrevClose(codes, prevDate);
        Map<String, Double> volumeRatioMap = loadVolumeRatio(endDate);

        // 4. 并行抓取当日分时K线并计算指标
        List<Result> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threadPoolSize);
        List<Future<Result>> futures = new ArrayList<>();
        for (String code : codes)
        {
            futures.add(pool.submit(() -> analyze(code, endDate, prevCloseMap.get(code), volumeRatioMap.get(code))));
        }
        pool.shutdown();
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
                log.debug("尾盘分析任务失败: {}", e.getMessage());
            }
        }
        log.info("分析完成，命中 {} 只", results.size());

        // 5. 按当日涨幅降序排序
        results.sort(Comparator.comparingDouble((Result r) -> r.closePct).reversed());

        // 6. 生成分组
        if (results.isEmpty())
        {
            log.warn("无命中股票，不生成分组");
            return;
        }
        createGroup(results, endDate);

        // 7. 邮件推送结果到手机
        notifyByEmail(results, endDate);

        log.info("========== 尾盘推荐完成 ==========");
    }

    /**
     * 把推荐结果整理成邮件正文推送到手机
     */
    private void notifyByEmail(List<Result> results, LocalDate endDate)
    {
        // 查询股票名称
        Map<String, String> nameMap = new HashMap<>();
        List<String> resultCodes = results.stream().map(r -> r.code).collect(Collectors.toList());
        List<Map<String, Object>> nameRows = stockMapper.selectNamesByCodes(resultCodes);
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

        StringBuilder sb = new StringBuilder();
        // 第一段：推荐股票名称（代码），方便直接复制导入同花顺
        sb.append("推荐股票 ").append(results.size()).append(" 只：\n");
        int i = 1;
        for (Result r : results)
        {
            sb.append(i++).append(". ")
                    .append(nameMap.getOrDefault(r.code, "")).append("（").append(r.code).append("）\n");
        }
        sb.append("\n");
        // 第二段：明细指标
        sb.append("明细（涨幅/收盘位/尾盘/量比）：\n");
        i = 1;
        for (Result r : results)
        {
            sb.append(i++).append(". ")
                    .append(nameMap.getOrDefault(r.code, "")).append("(").append(r.code).append(")")
                    .append("  涨").append(String.format("%.2f", r.closePct)).append("%")
                    .append("  收盘位").append(String.format("%.2f", r.closePos))
                    .append("  尾盘").append(String.format("%+.2f", r.tailChange)).append("%")
                    .append(r.volumeRatio == null ? "" : "  量比" + String.format("%.2f", r.volumeRatio))
                    .append("\n");
        }
        mailNotifyService.send(notifySubject + " " + endDate, sb.toString());
    }

    private void collectRisingShake(Map<String, String> candidateMap)
    {
        List<StockRisingState> states = stockRisingStateMapper.selectAll();
        if (states == null)
        {
            return;
        }
        for (StockRisingState st : states)
        {
            if (st.getStockCode() == null || st.getStatus() == null)
            {
                continue;
            }
            if ("上升".equals(st.getStatus()) || "震荡".equals(st.getStatus()))
            {
                candidateMap.putIfAbsent(st.getStockCode(), st.getStatus());
            }
        }
    }

    private void collectLimitUp(Map<String, String> candidateMap, LocalDate prevDate)
    {
        List<String> excludePrefixes = parseExcludePrefixes();
        List<StockLimitUpRecord> records = stockLimitUpRecordMapper.selectByTradeDate(prevDate.toString());
        if (records == null)
        {
            return;
        }
        for (StockLimitUpRecord r : records)
        {
            String code = r.getSecuCode();
            if (code == null || code.isEmpty())
            {
                continue;
            }
            if (excludeSt && r.getSecuName() != null && r.getSecuName().toUpperCase().contains("ST"))
            {
                continue;
            }
            if (startsWithAny(code, excludePrefixes))
            {
                continue;
            }
            int board = boardOf(r.getUpNum());
            if (board == 1 || board == 2)
            {
                candidateMap.putIfAbsent(code, "首二板");
            }
        }
    }

    /**
     * 单只股票分析：抓当日分时K线，计算尾盘可买指标，命中返回 Result，否则返回 null
     */
    private Result analyze(String code, LocalDate endDate, Double prevClose, Double volumeRatio)
    {
        try
        {
            List<StockKline> bars = tencentKlineDataSource.fetchMinuteKline(code, inferMarket(code), minuteType, endDate.toString());
            if (bars == null || bars.isEmpty())
            {
                return null;
            }
            bars = bars.stream()
                    .filter(k -> k.getTradeTime() != null && k.getClosePrice() != null)
                    .sorted(Comparator.comparing(StockKline::getTradeTime))
                    .collect(Collectors.toList());
            if (bars.size() < 3)
            {
                return null;
            }
            if (prevClose == null || prevClose <= 0)
            {
                return null;
            }

            StockKline first = bars.get(0);
            StockKline last = bars.get(bars.size() - 1);
            double open = doubleOf(first.getOpenPrice());
            double close = doubleOf(last.getClosePrice());
            double dayHigh = Double.MIN_VALUE;
            double dayLow = Double.MAX_VALUE;
            for (StockKline k : bars)
            {
                double h = doubleOf(k.getHighPrice());
                double l = doubleOf(k.getLowPrice());
                if (h > dayHigh) dayHigh = h;
                if (l < dayLow) dayLow = l;
            }

            double openPct = (open - prevClose) / prevClose * 100;
            double closePct = (close - prevClose) / prevClose * 100;
            double closePos = dayHigh > dayLow ? (close - dayLow) / (dayHigh - dayLow) : 0;

            double tailChange = 0;
            if (bars.size() >= tailBars + 1)
            {
                double tailStart = doubleOf(bars.get(bars.size() - tailBars - 1).getClosePrice());
                if (tailStart > 0)
                {
                    tailChange = (close - tailStart) / tailStart * 100;
                }
            }

            if (closePct >= maxGain) return null;
            if (closePct < minGain) return null;
            if (closePos < minClosePos) return null;
            if (tailChange < minTailChange) return null;
            if (volumeRatio != null && volumeRatio < minVolumeRatio) return null;

            Result r = new Result();
            r.code = code;
            r.openPct = openPct;
            r.closePct = closePct;
            r.closePos = closePos;
            r.tailChange = tailChange;
            r.volumeRatio = volumeRatio;
            return r;
        }
        catch (Exception e)
        {
            log.debug("分析失败 code={}, error={}", code, e.getMessage());
            return null;
        }
    }

    private void createGroup(List<Result> results, LocalDate endDate)
    {
        String dailyGroupName = groupName + "_" + endDate;
        if (findGroupByName(dailyGroupName) != null)
        {
            log.info("分组 [{}] 已存在，跳过", dailyGroupName);
            return;
        }
        String[] codes = results.stream().map(r -> r.code).toArray(String[]::new);
        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(codes);
        group.setCreateBy("system");
        group.setRemark(String.format("%s（截止%s，尾盘可买：未涨停(涨<%.1f%%)+涨幅≥%.1f%%+收盘位≥%.2f+尾盘30m≥%.1f%%+量比≥%.1f，命中%d只）",
                dailyGroupName, endDate, maxGain, minGain, minClosePos, minTailChange, minVolumeRatio, results.size()));
        stockGroupService.insertGroup(group);

        log.info("生成尾盘推荐分组 [{}]，命中 {} 只", dailyGroupName, results.size());
        for (Result r : results)
        {
            log.info("    {} 涨{:.2f}% 收盘位{:.2f} 尾盘{:.2f}% 量比{}",
                    r.code, r.closePct, r.closePos, r.tailChange, r.volumeRatio == null ? "-" : String.format("%.2f", r.volumeRatio));
        }
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

    private Map<String, Double> loadVolumeRatio(LocalDate endDate)
    {
        Map<String, Double> map = new HashMap<>();
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectByTradeDates(Collections.singletonList(Date.valueOf(endDate)));
        if (quotes != null)
        {
            for (StockDailyQuote q : quotes)
            {
                if (q.getStockCode() != null && q.getVolumeRatio() != null)
                {
                    map.put(q.getStockCode(), q.getVolumeRatio().doubleValue());
                }
            }
        }
        return map;
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

    private double doubleOf(BigDecimal v)
    {
        return v == null ? 0d : v.doubleValue();
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

    private static class Result
    {
        private String code;
        private double openPct;
        private double closePct;
        private double closePos;
        private double tailChange;
        private Double volumeRatio;
    }
}
