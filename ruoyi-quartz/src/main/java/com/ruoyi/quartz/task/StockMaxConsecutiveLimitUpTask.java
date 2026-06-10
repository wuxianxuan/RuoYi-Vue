package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 涨停股票历史最大连板数分析任务
 * <p>
 * 功能：查询今日首板涨停的股票（up_num为空），从 stock_limit_up_record 表中解析 up_num 字段（格式"X天Y板"），
 * 找出每只股票在最近3年内历史最大连板数及对应日期，筛选出大于等于指定连板数阈值的记录，结果输出为CSV文件。
 * <p>
 * 入参格式：<code>日期,最低连板数</code>，两部分均可省略。
 * <ul>
 *   <li>空 / "5" → 今天，≥5板</li>
 *   <li>"20250610" → 2025-06-10，≥5板</li>
 *   <li>"20250610,3" → 2025-06-10，≥3板</li>
 *   <li>",8" → 今天，≥8板</li>
 * </ul>
 * <p>
 * 数据依赖：本任务依赖 stockLimitUpCollectTask 先行采集当日涨停数据。
 *
 * @author ruoyi
 */
@Component("stockMaxConsecutiveLimitUpTask")
public class StockMaxConsecutiveLimitUpTask {

    private static final Logger log = LoggerFactory.getLogger(StockMaxConsecutiveLimitUpTask.class);

    /** 历史回溯年数 */
    private static final int LOOKBACK_YEARS = 3;

    /** 默认最低连板数阈值 */
    private static final int DEFAULT_MIN_BOARD_COUNT = 5;

    /** up_num 字段解析正则：匹配 "X天Y板" 格式，提取 Y（连板数） */
    private static final Pattern UP_NUM_PATTERN = Pattern.compile("(\\d+)天(\\d+)板");

    /** 入参分隔符 */
    private static final String PARAM_SEPARATOR = ",";

    /** CSV 表头 */
    private static final String CSV_HEADER = "﻿序号,股票代码,股票名称,历史最大连板数,对应日期";

    /** CSV 输出目录，可通过配置 stock.analysis.output-dir 自定义 */
    @Value("${stock.analysis.output-dir:./data}")
    private String outputDir;

    @Resource
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    /** 无参调用：分析今日涨停股，默认≥5板 */
    public void analyze() {
        analyze(null);
    }

    /**
     * 分析涨停股的历史最大连板数，筛选 ≥ 最低连板数的记录，结果写入CSV文件
     *
     * @param params 入参格式："日期,最低连板数"（两部分均可省略，日期格式 yyyyMMdd）
     *               示例："20250610" / "20250610,3" / ",8" / "5"
     */
    public void analyze(String params) {
        TaskParams p = parseParams(params);
        log.info("开始分析 {} 涨停股票的历史最大连板数（回溯{}年，最低≥{}板）...",
                p.tradeDate, LOOKBACK_YEARS, p.minBoardCount);

        // 1. 查询今日首板涨停的股票列表（up_num为空，即刚开始涨停）
        List<StockLimitUpRecord> todayRecords = stockLimitUpRecordMapper.selectFirstBoardStocksByDate(p.tradeDate);
        if (todayRecords == null || todayRecords.isEmpty()) {
            log.warn("未查询到 {} 的首板涨停股票数据，请确认涨停采集任务(stockLimitUpCollectTask)已先行执行", p.tradeDate);
            return;
        }

        List<String> limitUpCodes = todayRecords.stream()
                .map(StockLimitUpRecord::getSecuCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        log.info("{} 首板涨停股票数量: {}", p.tradeDate, limitUpCodes.size());

        // 构建股票代码→名称映射
        Map<String, String> codeNameMap = todayRecords.stream()
                .filter(r -> r.getSecuCode() != null)
                .collect(Collectors.toMap(
                        StockLimitUpRecord::getSecuCode,
                        r -> r.getSecuName() != null ? r.getSecuName() : "",
                        (a, b) -> a));

        // 2. 查询近3年所有涨停记录
        String startDate = LocalDate.parse(p.tradeDate, DateTimeFormatter.ofPattern("yyyyMMdd"))
                .minusYears(LOOKBACK_YEARS)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        List<StockLimitUpRecord> historyRecords = stockLimitUpRecordMapper.selectByCodesAndDateRange(
                limitUpCodes, startDate, p.tradeDate);
        log.info("查询到 {} 条历史涨停记录 ({} ~ {})", historyRecords.size(), startDate, p.tradeDate);

        // 3. 按股票代码分组，找出每只股票的历史最大连板数及对应日期
        Map<String, List<StockLimitUpRecord>> recordsByCode = historyRecords.stream()
                .filter(r -> r.getSecuCode() != null)
                .collect(Collectors.groupingBy(StockLimitUpRecord::getSecuCode));

        // 4. 逐只分析，收集结果行（不含序号）
        List<String[]> allRows = new ArrayList<>();
        for (String stockCode : limitUpCodes) {
            List<StockLimitUpRecord> records = recordsByCode.getOrDefault(stockCode, Collections.emptyList());
            MaxBoardResult result = findMaxBoardCount(records);
            String stockName = codeNameMap.getOrDefault(stockCode, "");
            allRows.add(new String[]{
                    "",     // 序号占位，筛选后重新编排
                    stockCode,
                    stockName,
                    String.valueOf(result.maxBoardCount),
                    result.maxDate != null ? result.maxDate : ""
            });
        }

        // 5. 按阈值筛选并按连板数降序排列（连板数在索引3）
        List<String[]> filteredRows = allRows.stream()
                .filter(row -> Integer.parseInt(row[3]) >= p.minBoardCount)
                .sorted((a, b) -> Integer.compare(Integer.parseInt(b[3]), Integer.parseInt(a[3])))
                .collect(Collectors.toList());

        // 重新编序号
        for (int i = 0; i < filteredRows.size(); i++) {
            filteredRows.get(i)[0] = String.valueOf(i + 1);
        }

        log.info("符合≥{}板条件的股票: {} 只（共 {} 只首板涨停）", p.minBoardCount, filteredRows.size(), allRows.size());

        // 6. 控制台日志输出
        printTable(filteredRows, p);

        // 7. 写入CSV文件
        String csvPath = writeCsv(p, filteredRows);
        log.info("分析结果已写入CSV文件: {}", csvPath);
        log.info("========== 分析完成 ==========");
    }

    /**
     * 解析入参：格式 "日期,最低连板数"，两部分均可省略
     */
    private TaskParams parseParams(String params) {
        String datePart = null;
        String boardPart = null;

        if (params != null && !params.trim().isEmpty()) {
            String s = params.trim();
            if (s.contains(PARAM_SEPARATOR)) {
                String[] parts = s.split(PARAM_SEPARATOR, 2);
                datePart = parts[0].trim();
                boardPart = parts.length > 1 ? parts[1].trim() : "";
            } else {
                // 没有逗号：根据长度判断是日期还是连板数
                if (s.length() == 8 && s.matches("\\d{8}")) {
                    datePart = s;
                } else {
                    boardPart = s;
                }
            }
        }

        String tradeDate = (datePart != null && !datePart.isEmpty())
                ? datePart
                : LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        int minBoardCount = DEFAULT_MIN_BOARD_COUNT;
        if (boardPart != null && !boardPart.isEmpty()) {
            try {
                minBoardCount = Integer.parseInt(boardPart);
            } catch (NumberFormatException e) {
                log.warn("无法解析最低连板数参数 '{}'，使用默认值 {}", boardPart, DEFAULT_MIN_BOARD_COUNT);
            }
        }

        return new TaskParams(tradeDate, minBoardCount);
    }

    /**
     * 控制台打印结果表格
     */
    private void printTable(List<String[]> rows, TaskParams params) {
        log.info("========== 涨停股票历史最大连板数（≥{}板）分析结果 ==========", params.minBoardCount);
        if (rows.isEmpty()) {
            log.info("（无符合条件的记录）");
            return;
        }
        log.info("| 序号 | 股票代码   | 股票名称 | 历史最大连板数 | 对应日期   |");
        log.info("|------|-----------|---------|--------------|-----------|");
        for (String[] row : rows) {
            log.info("| {:<4} | {:<9} | {:<7} | {:<12} | {:<9} |",
                    row[0], row[1], row[2], row[3], row[4]);
        }
    }

    /**
     * 将结果写入CSV文件（UTF-8 BOM，兼容Excel直接打开）
     */
    private String writeCsv(TaskParams params, List<String[]> rows) {
        try {
            Path dir = Paths.get(outputDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            String filename = String.format("limit_up_max_board_%s_min%d.csv",
                    params.tradeDate, params.minBoardCount);
            Path file = dir.resolve(filename);

            try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                writer.write(CSV_HEADER);
                writer.newLine();
                for (String[] row : rows) {
                    // row格式: [序号, 代码, 名称, 连板数, 日期]
                    // 写入CSV时附带序号
                    writer.write(toCsvLine(row));
                    writer.newLine();
                }
            }
            return file.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("写入CSV文件失败: {}", e.getMessage(), e);
            return "[写入失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 将一行数据转为CSV行字符串（含逗号转义）
     */
    private String toCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = fields[i] != null ? fields[i] : "";
            if (f.contains(",") || f.contains("\"") || f.contains("\n")) {
                f = f.replace("\"", "\"\"");
                sb.append('"').append(f).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    /**
     * 从一只股票的历史涨停记录中找出最大连板数及对应日期。
     * 同一交易日该股票可能有多条记录（分属不同板块），取其 up_num 解析后的最大值作为该日的连板数。
     */
    MaxBoardResult findMaxBoardCount(List<StockLimitUpRecord> records) {
        if (records == null || records.isEmpty()) {
            return new MaxBoardResult(0, null);
        }

        Map<String, Integer> dateBoardMap = new LinkedHashMap<>();
        for (StockLimitUpRecord record : records) {
            String date = record.getTradeDate();
            int boardCount = parseBoardCount(record.getUpNum());
            if (boardCount > 0) {
                dateBoardMap.merge(date, boardCount, Math::max);
            }
        }

        int maxBoard = 0;
        String maxDate = null;
        for (Map.Entry<String, Integer> entry : dateBoardMap.entrySet()) {
            if (entry.getValue() > maxBoard) {
                maxBoard = entry.getValue();
                maxDate = entry.getKey();
            }
        }

        return new MaxBoardResult(maxBoard, maxDate);
    }

    /**
     * 解析 up_num 字段获取连板数
     * <ul>
     *   <li>"3天2板" → 2</li>
     *   <li>"7天5板" → 5</li>
     *   <li>"首板" → 1</li>
     * </ul>
     */
    int parseBoardCount(String upNum) {
        if (upNum == null || upNum.trim().isEmpty()) {
            return 0;
        }
        String s = upNum.trim();

        Matcher matcher = UP_NUM_PATTERN.matcher(s);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(2));
        }

        if (s.contains("首板")) {
            return 1;
        }

        Matcher numOnly = Pattern.compile("(\\d+)").matcher(s);
        if (numOnly.find()) {
            return Integer.parseInt(numOnly.group(1));
        }

        return 0;
    }

    // ---------- 内部类 ----------

    /** 任务入参 */
    static class TaskParams {
        final String tradeDate;
        final int minBoardCount;

        TaskParams(String tradeDate, int minBoardCount) {
            this.tradeDate = tradeDate;
            this.minBoardCount = minBoardCount;
        }
    }

    /** 最大连板数查询结果 */
    static class MaxBoardResult {
        final int maxBoardCount;
        final String maxDate;

        MaxBoardResult(int maxBoardCount, String maxDate) {
            this.maxBoardCount = maxBoardCount;
            this.maxDate = maxDate;
        }
    }
}
