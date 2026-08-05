package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * 基于板块涨停热度分析，输出 Top N 热门板块及板块内强势股票 CSV
 */
@Service
public class StockRecommendServiceImpl implements IStockRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendServiceImpl.class);

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    // ==================== 配置项 ====================

    @Value("${stock.recommend.excluded-plates:}")
    private String excludedPlates;

    @Value("${stock.recommend.plate-day-weight:10}")
    private int plateDayWeight;

    @Value("${stock.recommend.plate-count-weight:10}")
    private int plateCountWeight;

    @Value("${stock.recommend.top-plates:7}")
    private int topPlates;

    @Value("${stock.recommend.lookback-days:7}")
    private int lookbackDays;

    @Value("${stock.analysis.output-dir:./data}")
    private String outputDir;

    /** CSV BOM header for UTF-8 Excel compatibility */
    private static final String CSV_BOM = "﻿";

    /** 板块推荐 CSV 表头 */
    private static final String PLATE_CSV_HEADER = "板块名称,板块得分,股票代码,股票名称,股票上榜天数,股票最新上榜时间";

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
        List<StockLimitUpRecord> allRecords =
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
        List<StockLimitUpRecord> filteredRecords = allRecords.stream()
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
        Map<String, List<StockLimitUpRecord>> recordsByPlate =
                filteredRecords.stream()
                        .collect(Collectors.groupingBy(r -> r.getPlateSecuName().trim()));

        // 7天内所有未过滤板块的涨停总记录数（用于计算涨停占比分）
        int totalRecordCount = filteredRecords.size();

        // 计算每个板块的得分
        List<PlateScore> plateScores = new ArrayList<>();
        for (Map.Entry<String, List<StockLimitUpRecord>> entry : recordsByPlate.entrySet())
        {
            String plateName = entry.getKey();
            List<StockLimitUpRecord> plateRecords = entry.getValue();

            // 出现天数（去重 trade_date）
            long appearDays = plateRecords.stream()
                    .map(StockLimitUpRecord::getTradeDate)
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
            Map<String, List<StockLimitUpRecord>> stocksInPlate =
                    ps.records.stream()
                            .filter(r -> r.getSecuCode() != null)
                            .collect(Collectors.groupingBy(StockLimitUpRecord::getSecuCode));

            // 计算每只股票的统计信息
            List<StockInPlate> stockList = new ArrayList<>();
            for (Map.Entry<String, List<StockLimitUpRecord>> se : stocksInPlate.entrySet())
            {
                String stockCode = se.getKey();
                List<StockLimitUpRecord> stockRecords = se.getValue();

                int appearCount = stockRecords.size();
                String stockName = stockRecords.get(0).getSecuName() != null
                        ? stockRecords.get(0).getSecuName() : "";

                // 最新上榜时间：找 trade_date 最大，相同 trade_date 找 time 最晚的
                StockLimitUpRecord latest = stockRecords.stream()
                        .max(Comparator.comparing(StockLimitUpRecord::getTradeDate)
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

    // ==================== 辅助方法 ====================

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
        final List<StockLimitUpRecord> records;

        PlateScore(String plateName, BigDecimal totalScore, BigDecimal dayScore,
                   BigDecimal countScore, int appearDays, int recordCount,
                   List<StockLimitUpRecord> records)
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
}
