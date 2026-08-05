package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockRecommend;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票推荐引擎实现
 *
 * 策略1：均线多头排列 + 板块共振（最高80分）
 * 策略2：MACD零轴金叉 + 放量（最高80分）
 * 融合：取最优策略得分
 */
@Service
public class StockRecommendServiceImpl implements IStockRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockRecommendMapper stockRecommendMapper;

    /** 最低K线数量要求 */
    private static final int MIN_KLINES = 20;

    /** 最高MA得分 */
    private static final BigDecimal MAX_MA_SCORE = BigDecimal.valueOf(50);

    /** 最高MACD得分 */
    private static final BigDecimal MAX_MACD_SCORE = BigDecimal.valueOf(60);

    /** 放量加分 */
    private static final BigDecimal VOL_BONUS = BigDecimal.valueOf(20);

    /** 板块涨停股单只加分 */
    private static final BigDecimal PLATE_STOCK_BONUS = BigDecimal.valueOf(10);

    /** 板块加分上限 */
    private static final BigDecimal PLATE_BONUS_MAX = BigDecimal.valueOf(30);

    /** 量比阈值 */
    private static final BigDecimal VOL_RATIO_THRESHOLD = new BigDecimal("1.5");

    // ==================== 板块推荐引擎配置 ====================

    @org.springframework.beans.factory.annotation.Value("${stock.recommend.excluded-plates:}")
    private String excludedPlates;

    @org.springframework.beans.factory.annotation.Value("${stock.recommend.plate-day-weight:10}")
    private int plateDayWeight;

    @org.springframework.beans.factory.annotation.Value("${stock.recommend.plate-count-weight:10}")
    private int plateCountWeight;

    @org.springframework.beans.factory.annotation.Value("${stock.recommend.top-plates:7}")
    private int topPlates;

    @org.springframework.beans.factory.annotation.Value("${stock.recommend.lookback-days:7}")
    private int lookbackDays;

    @org.springframework.beans.factory.annotation.Value("${stock.analysis.output-dir:./data}")
    private String outputDir;

    /** CSV BOM header for UTF-8 Excel compatibility */
    private static final String CSV_BOM = "﻿";

    /** 板块推荐 CSV 表头 */
    private static final String PLATE_CSV_HEADER = "板块名称,板块得分,股票代码,股票名称,股票上榜天数,股票最新上榜时间";

    @Override
    public int getPlateHeat(String plateName, String tradeDate)
    {
        if (plateName == null || plateName.isEmpty() || tradeDate == null || tradeDate.isEmpty())
        {
            return 0;
        }
        try
        {
            // 直接查询 stock_limit_up_record 中该板块的涨停股数
            List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records =
                    stockLimitUpRecordMapper.collectStockData(null);
            if (records == null) return 0;

            int count = 0;
            for (com.ruoyi.system.domain.stock.StockLimitUpRecord r : records)
            {
                if (plateName.equals(r.getPlateSecuName())
                        && r.getPlateStockUpNum() != null
                        && r.getPlateStockUpNum() > 0)
                {
                    count = Math.max(count, r.getPlateStockUpNum());
                }
            }
            return count;
        }
        catch (Exception e)
        {
            log.debug("查询板块热度失败: plateName={}, error={}", plateName, e.getMessage());
            return 0;
        }
    }

    @Override
    public BigDecimal getStockPlateHeat(String stockCode, String tradeDate)
    {
        // 使用前一天的数据（如果是当天盘后则用当天）
        try
        {
            List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records =
                    stockLimitUpRecordMapper.collectStockData(null);
            if (records == null || records.isEmpty()) return BigDecimal.ZERO;

            // 找到该股票所属板块的热度
            Set<String> seenPlates = new HashSet<>();
            int totalHeat = 0;
            int plateCount = 0;

            for (com.ruoyi.system.domain.stock.StockLimitUpRecord r : records)
            {
                if (tradeDate.equals(r.getTradeDate()) && r.getPlateStockUpNum() != null)
                {
                    String plateName = r.getPlateSecuName();
                    if (plateName != null && seenPlates.add(plateName))
                    {
                        totalHeat += r.getPlateStockUpNum();
                        plateCount++;
                    }
                }
            }

            if (plateCount == 0) return BigDecimal.ZERO;
            return BigDecimal.valueOf(totalHeat)
                    .divide(BigDecimal.valueOf(plateCount), 4, RoundingMode.HALF_UP);
        }
        catch (Exception e)
        {
            log.debug("查询股票板块热度失败: stockCode={}, error={}", stockCode, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public int executeRecommend(String tradeDate, int topN)
    {
        log.info("========== 推荐引擎开始: tradeDate={}, topN={} ==========", tradeDate, topN);

        try
        {
            // 1. 获取全量 SH/SZ 股票
            List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
            if (stocks == null || stocks.isEmpty())
            {
                log.warn("未找到股票记录");
                return 0;
            }
            log.info("候选股票池: {} 只", stocks.size());

            // 2. 计算前一天（最近的交易日）日期
            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            // 3. 逐股评分
            List<StockRecommend> candidates = new ArrayList<>();

            for (Stock stock : stocks)
            {
                try
                {
                    String stockCode = stock.getStockCode();
                    if (stockCode == null || stockCode.isEmpty()) continue;

                    // 获取最新指标
                    StockIndicator indicator = stockIndicatorMapper.selectLatestByStockCode(stockCode, "D");
                    if (indicator == null)
                    {
                        continue;
                    }

                    // 获取板块热度
                    BigDecimal plateHeat = getStockPlateHeat(stockCode, yesterday);

                    // 策略1: 均线多头排列 + 板块共振
                    BigDecimal score1 = scoreStrategy1(indicator, plateHeat);

                    // 策略2: MACD零轴金叉 + 放量
                    BigDecimal score2 = scoreStrategy2(indicator);

                    // 融合：取最优策略得分
                    BigDecimal totalScore = score1.max(score2);

                    if (totalScore.compareTo(BigDecimal.ZERO) > 0)
                    {
                        String reason = buildReason(score1, score2, indicator, plateHeat);
                        StockRecommend rec = new StockRecommend();
                        rec.setRecommendDate(java.sql.Date.valueOf(LocalDate.now()));
                        rec.setStockId(stock.getId());
                        rec.setStockCode(stock.getStockCode());
                        rec.setStockName(stock.getStockName());
                        rec.setTotalScore(totalScore);
                        rec.setTrendScore(score1.max(score2));
                        rec.setPlateScore(plateHeat);
                        rec.setFundScore(BigDecimal.ZERO);
                        rec.setBaseScore(BigDecimal.ZERO);
                        rec.setReason(reason);
                        rec.setStatus("0");
                        rec.setCreateBy("system");
                        rec.setUpdateBy("system");
                        candidates.add(rec);
                    }
                }
                catch (Exception e)
                {
                    log.debug("评分异常: stockCode={}, error={}", stock.getStockCode(), e.getMessage());
                }
            }

            log.info("评分完成: {} 只股票有得分", candidates.size());

            // 4. 按总分降序排列
            candidates.sort(Comparator.comparing(StockRecommend::getTotalScore).reversed());

            // 5. 取 Top N
            List<StockRecommend> topList = candidates.subList(0, Math.min(topN, candidates.size()));

            // 6. 幂等：删除当天旧数据 → 插入新数据
            String todayStr = LocalDate.now().toString();
            stockRecommendMapper.deleteByDate(todayStr);

            if (!topList.isEmpty())
            {
                stockRecommendMapper.batchInsertRecommend(topList);
                log.info("推荐结果入库: {} 条", topList.size());
            }

            log.info("========== 推荐引擎完成: 生成 {} 条推荐 ==========", topList.size());
            return topList.size();
        }
        catch (Exception e)
        {
            log.error("推荐引擎执行异常", e);
            return 0;
        }
    }

    // ==================== 板块热度推荐引擎 ====================

    @Override
    public void executePlateRecommend()
    {
        log.info("========== 板块热度推荐引擎开始 ==========");

        // 1. 获取最新交易日
        String latestDate = stockLimitUpRecordMapper.selectLatestTradeDate();
        if (latestDate == null || latestDate.isEmpty())
        {
            log.warn("stock_limit_up_record 表中无数据，请先执行涨停采集任务");
            return;
        }
        log.info("最新交易日: {}", latestDate);

        // 2. 计算回溯起始日期
        String startDate = LocalDate.parse(latestDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                .minusDays(lookbackDays)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // 3. 查询过去 N 天所有涨停记录
        List<com.ruoyi.system.domain.stock.StockLimitUpRecord> allRecords =
                stockLimitUpRecordMapper.selectByDateRange(startDate, latestDate);
        if (allRecords == null || allRecords.isEmpty())
        {
            log.warn("未查询到 {}-{} 的涨停记录", startDate, latestDate);
            return;
        }
        log.info("查询到 {} 条涨停记录 ({} ~ {})", allRecords.size(), startDate, latestDate);

        // 4. 解析排除板块名单
        Set<String> excludedPlateSet = parseExcludedPlates();
        log.info("排除板块: {}", excludedPlateSet.isEmpty() ? "无" : excludedPlateSet);

        // 5. 过滤排除板块后的记录
        List<com.ruoyi.system.domain.stock.StockLimitUpRecord> filteredRecords = allRecords.stream()
                .filter(r -> r.getPlateSecuName() != null
                        && !excludedPlateSet.contains(r.getPlateSecuName().trim()))
                .collect(Collectors.toList());
        log.info("过滤后剩余 {} 条记录", filteredRecords.size());

        if (filteredRecords.isEmpty())
        {
            log.warn("过滤后无可用涨停记录");
            return;
        }

        // 6. 按板块分组，计算各项统计指标
        Map<String, List<com.ruoyi.system.domain.stock.StockLimitUpRecord>> recordsByPlate =
                filteredRecords.stream()
                        .collect(Collectors.groupingBy(r -> r.getPlateSecuName().trim()));

        // 7天内所有未过滤板块的涨停总记录数（用于计算涨停占比分）
        int totalRecordCount = filteredRecords.size();

        // 计算每个板块的得分
        List<PlateScore> plateScores = new ArrayList<>();
        for (Map.Entry<String, List<com.ruoyi.system.domain.stock.StockLimitUpRecord>> entry : recordsByPlate.entrySet())
        {
            String plateName = entry.getKey();
            List<com.ruoyi.system.domain.stock.StockLimitUpRecord> plateRecords = entry.getValue();

            // 出现天数（去重 trade_date）
            long appearDays = plateRecords.stream()
                    .map(com.ruoyi.system.domain.stock.StockLimitUpRecord::getTradeDate)
                    .distinct()
                    .count();

            // 出现频率分 = (出现天数 / 回溯天数) × 权重
            BigDecimal dayScore = BigDecimal.valueOf(appearDays)
                    .divide(BigDecimal.valueOf(lookbackDays), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(plateDayWeight));

            // 涨停占比分 = (板块记录数 / 总记录数) × 权重
            BigDecimal countScore = BigDecimal.valueOf(plateRecords.size())
                    .divide(BigDecimal.valueOf(totalRecordCount), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(plateCountWeight));

            // 总分
            BigDecimal totalScore = dayScore.add(countScore);

            plateScores.add(new PlateScore(plateName, totalScore, dayScore, countScore,
                    (int) appearDays, plateRecords.size(), plateRecords));
        }

        // 7. 按总分降序，取 Top N 板块
        plateScores.sort(Comparator.comparing(PlateScore::getTotalScore).reversed());
        List<PlateScore> topPlateScores = plateScores.subList(0, Math.min(topPlates, plateScores.size()));

        log.info("========== Top {} 板块评分 ==========", topPlateScores.size());
        for (int i = 0; i < topPlateScores.size(); i++)
        {
            PlateScore ps = topPlateScores.get(i);
            log.info("  {}. {} 总分={} (出现频率分={}, 涨停占比分={}, 出现{}天, 涨停{}次)",
                    i + 1, ps.plateName, ps.totalScore.setScale(2, RoundingMode.HALF_UP),
                    ps.dayScore.setScale(2, RoundingMode.HALF_UP),
                    ps.countScore.setScale(2, RoundingMode.HALF_UP),
                    ps.appearDays, ps.recordCount);
        }

        // 8. 对每个 Top 板块，列出股票并按规则排序
        List<String[]> csvRows = new ArrayList<>();
        for (PlateScore ps : topPlateScores)
        {
            // 按股票分组统计
            Map<String, List<com.ruoyi.system.domain.stock.StockLimitUpRecord>> stocksInPlate =
                    ps.records.stream()
                            .filter(r -> r.getSecuCode() != null)
                            .collect(Collectors.groupingBy(
                                    com.ruoyi.system.domain.stock.StockLimitUpRecord::getSecuCode));

            // 计算每只股票的统计信息
            List<StockInPlate> stockList = new ArrayList<>();
            for (Map.Entry<String, List<com.ruoyi.system.domain.stock.StockLimitUpRecord>> se : stocksInPlate.entrySet())
            {
                String stockCode = se.getKey();
                List<com.ruoyi.system.domain.stock.StockLimitUpRecord> stockRecords = se.getValue();

                int appearCount = stockRecords.size(); // 上榜天数（记录数=天数，因为每天一条）
                String stockName = stockRecords.get(0).getSecuName() != null
                        ? stockRecords.get(0).getSecuName() : "";

                // 最新上榜时间：找 trade_date 最大，相同 trade_date 找 time 最晚的
                com.ruoyi.system.domain.stock.StockLimitUpRecord latest = stockRecords.stream()
                        .max(Comparator.comparing(
                                com.ruoyi.system.domain.stock.StockLimitUpRecord::getTradeDate)
                                .thenComparing(r -> r.getTime() != null ? r.getTime() : ""))
                        .orElse(stockRecords.get(0));
                String latestTime = (latest.getTradeDate() != null ? latest.getTradeDate() : "")
                        + (latest.getTime() != null ? " " + latest.getTime() : "");

                stockList.add(new StockInPlate(stockCode, stockName, appearCount, latestTime));
            }

            // 排序：出现次数降序，最新时间升序（最近出现的排前面）
            stockList.sort(Comparator
                    .comparing(StockInPlate::getAppearCount).reversed()
                    .thenComparing(StockInPlate::getLatestTime));

            for (StockInPlate si : stockList)
            {
                csvRows.add(new String[]{
                        ps.plateName,
                        ps.totalScore.setScale(2, RoundingMode.HALF_UP).toString(),
                        si.stockCode,
                        si.stockName,
                        String.valueOf(si.appearCount),
                        si.latestTime
                });
            }
        }

        // 9. 写入 CSV
        String csvPath = writePlateCsv(latestDate, csvRows);
        log.info("板块推荐结果已写入CSV: {}", csvPath);
        log.info("========== 板块热度推荐引擎完成: {} 个板块, {} 只股票 ==========",
                topPlateScores.size(), csvRows.size());
    }

    /**
     * 解析排除板块名单
     */
    private Set<String> parseExcludedPlates()
    {
        if (excludedPlates == null || excludedPlates.trim().isEmpty())
        {
            return Collections.emptySet();
        }
        return Arrays.stream(excludedPlates.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 将板块推荐结果写入 CSV（UTF-8 BOM）
     */
    private String writePlateCsv(String tradeDate, List<String[]> rows)
    {
        try
        {
            Path dir = Paths.get(outputDir);
            if (!Files.exists(dir))
            {
                Files.createDirectories(dir);
            }
            String filename = String.format("plate_recommend_%s.csv", tradeDate);
            Path file = dir.resolve(filename);

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8))
            {
                writer.write(CSV_BOM + PLATE_CSV_HEADER);
                writer.newLine();
                for (String[] row : rows)
                {
                    writer.write(toCsvLine(row));
                    writer.newLine();
                }
            }
            return file.toAbsolutePath().toString();
        }
        catch (IOException e)
        {
            log.error("写入板块推荐CSV失败: {}", e.getMessage(), e);
            return "[写入失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 将字符串数组转为 CSV 行（处理逗号和引号转义）
     */
    private String toCsvLine(String[] fields)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++)
        {
            if (i > 0) sb.append(',');
            String f = fields[i] != null ? fields[i] : "";
            if (f.contains(",") || f.contains("\"") || f.contains("\n"))
            {
                f = f.replace("\"", "\"\"");
                sb.append('"').append(f).append('"');
            }
            else
            {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    // ==================== 内部类 ====================

    /** 板块评分结果 */
    static class PlateScore
    {
        final String plateName;
        final BigDecimal totalScore;
        final BigDecimal dayScore;
        final BigDecimal countScore;
        final int appearDays;
        final int recordCount;
        final List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records;

        PlateScore(String plateName, BigDecimal totalScore, BigDecimal dayScore,
                   BigDecimal countScore, int appearDays, int recordCount,
                   List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records)
        {
            this.plateName = plateName;
            this.totalScore = totalScore;
            this.dayScore = dayScore;
            this.countScore = countScore;
            this.appearDays = appearDays;
            this.recordCount = recordCount;
            this.records = records;
        }

        BigDecimal getTotalScore() { return totalScore; }
    }

    /** 板块内股票统计 */
    static class StockInPlate
    {
        final String stockCode;
        final String stockName;
        final int appearCount;
        final String latestTime;

        StockInPlate(String stockCode, String stockName, int appearCount, String latestTime)
        {
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.appearCount = appearCount;
            this.latestTime = latestTime;
        }

        int getAppearCount() { return appearCount; }
        String getLatestTime() { return latestTime; }
    }

    // ==================== 评分策略 ====================

    /**
     * 策略1：均线多头排列 + 板块共振
     *
     * 条件: MA5 > MA10 > MA20 > MA60（全部向上发散）
     * 得分: 多头成立50分 + 板块热度加成（每只涨停+10，上限30）
     */
    private BigDecimal scoreStrategy1(StockIndicator indicator, BigDecimal plateHeat)
    {
        if (indicator.getMa5() == null || indicator.getMa10() == null
                || indicator.getMa20() == null || indicator.getMa60() == null)
        {
            return BigDecimal.ZERO;
        }

        boolean isBull = indicator.getMa5().compareTo(indicator.getMa10()) > 0
                && indicator.getMa10().compareTo(indicator.getMa20()) > 0
                && indicator.getMa20().compareTo(indicator.getMa60()) > 0;

        if (!isBull)
        {
            return BigDecimal.ZERO;
        }

        BigDecimal score = MAX_MA_SCORE;  // 50分
        BigDecimal plateBonus = plateHeat.multiply(PLATE_STOCK_BONUS);
        if (plateBonus.compareTo(PLATE_BONUS_MAX) > 0)
        {
            plateBonus = PLATE_BONUS_MAX;
        }
        return score.add(plateBonus);
    }

    /**
     * 策略2：MACD零轴金叉 + 放量
     *
     * 条件: DIF > DEA 且 DIF > 0 且 DEA > 0（零轴上方金叉状态）
     * 量能: 量比 > 1.5
     * 得分: 金叉成立60分 + 放量20分
     */
    private BigDecimal scoreStrategy2(StockIndicator indicator)
    {
        if (indicator.getMacdDif() == null || indicator.getMacdDea() == null
                || indicator.getMacdBar() == null)
        {
            return BigDecimal.ZERO;
        }

        boolean goldenCross = indicator.getMacdDif().compareTo(BigDecimal.ZERO) > 0
                && indicator.getMacdDea().compareTo(BigDecimal.ZERO) > 0
                && indicator.getMacdDif().compareTo(indicator.getMacdDea()) > 0;

        if (!goldenCross)
        {
            return BigDecimal.ZERO;
        }

        BigDecimal score = MAX_MACD_SCORE;  // 60分

        // 放量加成
        if (indicator.getVolRatio() != null
                && indicator.getVolRatio().compareTo(VOL_RATIO_THRESHOLD) > 0)
        {
            score = score.add(VOL_BONUS);  // +20分
        }

        return score;
    }

    /**
     * 构造推荐理由
     */
    private String buildReason(BigDecimal score1, BigDecimal score2,
                               StockIndicator indicator, BigDecimal plateHeat)
    {
        List<String> reasons = new ArrayList<>();

        if (score1.compareTo(BigDecimal.ZERO) > 0)
        {
            reasons.add("均线多头排列");
            if (plateHeat.compareTo(BigDecimal.ZERO) > 0)
            {
                reasons.add("板块热度" + plateHeat);
            }
        }

        if (score2.compareTo(BigDecimal.ZERO) > 0)
        {
            reasons.add("MACD零轴金叉");
            if (indicator.getVolRatio() != null
                    && indicator.getVolRatio().compareTo(VOL_RATIO_THRESHOLD) > 0)
            {
                reasons.add("放量");
            }
        }

        return String.join(",", reasons);
    }
}
