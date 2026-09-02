package com.ruoyi.quartz.task;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockPlateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 涨停低吸高抛盈利概率统计定时任务
 *
 * 逻辑：查询最近 lookbackDays 天的涨停股（stock_limit_up_record），对每个涨停事件统计 7 种策略（A~G）的盈利概率，
 *   策略A~G：涨停日 T 之后，分别以 T+1/T+2、T+2/T+3 ... T+7/T+8 买入/卖出（每天推后一天）。
 *   每种策略同时统计两个口径：
 *     理论：买入日最低价买入、卖出日最高价卖出（完美择时，胜率偏高）；
 *     实盘：买入日收盘价买入、卖出日收盘价卖出（可实现，胜率更真实）。
 *   同时按股票所属的板块/概念（行业+概念）分类，分别统计各板块/概念在 A~G 策略下的胜率。
 *
 * 输出：日志打印总体结果；完整结果（总体 + 板块/概念）写入 CSV 文件。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockLimitUpWinRateTask.execute()
 */
@Component("stockLimitUpWinRateTask")
public class StockLimitUpWinRateTask
{
    private static final Logger log = LoggerFactory.getLogger(StockLimitUpWinRateTask.class);

    /** 策略数量（A~G） */
    private static final int STRATEGY_COUNT = 7;

    /** 策略标签 */
    private static final String[] STRATEGY_LABELS = {"A", "B", "C", "D", "E", "F", "G"};

    /** 回看天数（统计最近 N 天的涨停事件） */
    @Value("${stock.limit-up-win-rate.lookback-days:30}")
    private int lookbackDays;

    /** 板块/概念写入 CSV 的最小样本数（策略A实盘样本，低于该值不写入，避免噪声） */
    @Value("${stock.limit-up-win-rate.min-plate-sample:10}")
    private int minPlateSample;

    /** 结果输出目录 */
    @Value("${stock.analysis.output-dir:./data}")
    private String outputDir;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockPlateMapper stockPlateMapper;

    public void execute()
    {
        log.info("========== 涨停低吸高抛盈利概率统计开始 ==========");

        LocalDate today = LocalDate.now();
        String startDate = TradingDayUtils.getDateStr(today.minusDays(lookbackDays));
        String endDate = TradingDayUtils.getDateStr(today);

        // 1. 查询最近 lookbackDays 天的涨停事件（股票代码 + 涨停日期）
        List<StockLimitUpRecord> events = stockLimitUpRecordMapper.selectLimitUpEvents(startDate, endDate);
        if (events == null || events.isEmpty())
        {
            log.warn("最近 {} 天无涨停记录，跳过统计", lookbackDays);
            return;
        }
        log.info("涨停事件（去重后）共 {} 条", events.size());

        // 2. 查询这些股票的日K线
        List<String> codes = events.stream()
                .map(StockLimitUpRecord::getSecuCode)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (codes.isEmpty())
        {
            log.warn("无有效股票代码，跳过统计");
            return;
        }

        // K线查询范围：比涨停区间前后各多留几天，保证能取到 T+1~T+8
        String klineStart = today.minusDays(lookbackDays + 5).toString();
        String klineEnd = today.plusDays(3).toString();
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", klineStart, klineEnd);

        Map<String, List<StockKline>> klineMap = new HashMap<>();
        if (allKlines != null && !allKlines.isEmpty())
        {
            klineMap = allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));
            for (List<StockKline> list : klineMap.values())
            {
                list.sort(Comparator.comparing(StockKline::getTradeTime));
            }
        }
        log.info("查询到 {} 只股票的日K线数据", klineMap.size());

        // 3. 遍历涨停事件，计算总体与板块/概念的 A~G 胜率（理论 + 实盘）
        int[] theoTotals = new int[STRATEGY_COUNT];
        int[] theoWins = new int[STRATEGY_COUNT];
        BigDecimal[] theoReturnSums = new BigDecimal[STRATEGY_COUNT];
        int[] realTotals = new int[STRATEGY_COUNT];
        int[] realWins = new int[STRATEGY_COUNT];
        BigDecimal[] realReturnSums = new BigDecimal[STRATEGY_COUNT];
        Arrays.fill(theoReturnSums, BigDecimal.ZERO);
        Arrays.fill(realReturnSums, BigDecimal.ZERO);

        Map<String, List<String>> stockPlateMap = buildStockPlateMap(codes);
        Map<String, PlateStat> plateStats = new HashMap<>();
        int skipped = 0;

        for (StockLimitUpRecord event : events)
        {
            String code = event.getSecuCode();
            LocalDate t = toLocalDate(event.getTradeDate());
            if (code == null || t == null)
            {
                skipped++;
                continue;
            }
            List<StockKline> klines = klineMap.get(code);
            if (klines == null)
            {
                skipped++;
                continue;
            }

            // 定位涨停日之后的交易日序列（最多取 STRATEGY_COUNT+1 个，即到 T+8）
            List<StockKline> after = new ArrayList<>();
            for (StockKline k : klines)
            {
                LocalDate kd = toLocalDate(k.getTradeTime());
                if (kd == null || !kd.isAfter(t))
                {
                    continue;
                }
                after.add(k);
                if (after.size() >= STRATEGY_COUNT + 1)
                {
                    break;
                }
            }
            if (after.size() < 2)
            {
                skipped++;
                continue;
            }

            List<String> plates = stockPlateMap.get(code);

            for (int s = 0; s < STRATEGY_COUNT; s++)
            {
                // 策略 s：买入日 T+(s+1)、卖出日 T+(s+2)
                if (after.size() < s + 2)
                {
                    break;
                }
                StockKline buyK = after.get(s);
                StockKline sellK = after.get(s + 1);

                BigDecimal theoBuy = buyK.getLowPrice();
                BigDecimal theoSell = sellK.getHighPrice();
                boolean theoValid = theoBuy != null && theoSell != null && theoBuy.compareTo(BigDecimal.ZERO) > 0;
                BigDecimal theoRet = theoValid ? theoSell.subtract(theoBuy).divide(theoBuy, 6, RoundingMode.HALF_UP) : null;

                BigDecimal realBuy = buyK.getClosePrice();
                BigDecimal realSell = sellK.getClosePrice();
                boolean realValid = realBuy != null && realSell != null && realBuy.compareTo(BigDecimal.ZERO) > 0;
                BigDecimal realRet = realValid ? realSell.subtract(realBuy).divide(realBuy, 6, RoundingMode.HALF_UP) : null;

                // 总体统计
                if (theoValid)
                {
                    theoTotals[s]++;
                    theoReturnSums[s] = theoReturnSums[s].add(theoRet);
                    if (theoSell.compareTo(theoBuy) > 0) theoWins[s]++;
                }
                if (realValid)
                {
                    realTotals[s]++;
                    realReturnSums[s] = realReturnSums[s].add(realRet);
                    if (realSell.compareTo(realBuy) > 0) realWins[s]++;
                }

                // 板块/概念统计
                if (plates != null && !plates.isEmpty())
                {
                    for (String plate : plates)
                    {
                        PlateStat stat = plateStats.computeIfAbsent(plate, k -> new PlateStat());
                        if (theoValid)
                        {
                            stat.theoTotals[s]++;
                            stat.theoReturnSums[s] = stat.theoReturnSums[s].add(theoRet);
                            if (theoSell.compareTo(theoBuy) > 0) stat.theoWins[s]++;
                        }
                        if (realValid)
                        {
                            stat.realTotals[s]++;
                            stat.realReturnSums[s] = stat.realReturnSums[s].add(realRet);
                            if (realSell.compareTo(realBuy) > 0) stat.realWins[s]++;
                        }
                    }
                }
            }
        }

        // 4. 输出总体结果到日志
        log.info("========== 总体统计结果 ==========");
        for (int s = 0; s < STRATEGY_COUNT; s++)
        {
            log.info("策略{}：理论 样本{} 胜率{}% 平均收益{}% | 实盘 样本{} 胜率{}% 平均收益{}%",
                    STRATEGY_LABELS[s],
                    theoTotals[s], fmtRate(theoTotals[s], theoWins[s]), fmtAvg(theoTotals[s], theoReturnSums[s]),
                    realTotals[s], fmtRate(realTotals[s], realWins[s]), fmtAvg(realTotals[s], realReturnSums[s]));
        }
        log.info("无 T+1/T+2 数据跳过 {} 条", skipped);

        // 5. 写 CSV
        List<String> csvLines = new ArrayList<>();
        csvLines.add(csvLine("维度", "名称", "策略", "口径", "样本数", "盈利次数", "胜率%", "平均收益率%"));
        for (int s = 0; s < STRATEGY_COUNT; s++)
        {
            csvLines.add(csvLine("总体", "", STRATEGY_LABELS[s], "理论",
                    theoTotals[s], theoWins[s], rateCsv(theoTotals[s], theoWins[s]), avgCsv(theoTotals[s], theoReturnSums[s])));
            csvLines.add(csvLine("总体", "", STRATEGY_LABELS[s], "实盘",
                    realTotals[s], realWins[s], rateCsv(realTotals[s], realWins[s]), avgCsv(realTotals[s], realReturnSums[s])));
        }
        for (Map.Entry<String, PlateStat> e : plateStats.entrySet())
        {
            PlateStat ps = e.getValue();
            if (ps.realTotals[0] < minPlateSample)
            {
                continue;
            }
            for (int s = 0; s < STRATEGY_COUNT; s++)
            {
                csvLines.add(csvLine("板块", e.getKey(), STRATEGY_LABELS[s], "理论",
                        ps.theoTotals[s], ps.theoWins[s], rateCsv(ps.theoTotals[s], ps.theoWins[s]), avgCsv(ps.theoTotals[s], ps.theoReturnSums[s])));
                csvLines.add(csvLine("板块", e.getKey(), STRATEGY_LABELS[s], "实盘",
                        ps.realTotals[s], ps.realWins[s], rateCsv(ps.realTotals[s], ps.realWins[s]), avgCsv(ps.realTotals[s], ps.realReturnSums[s])));
            }
        }

        String csvPath = outputDir + "/limit_up_win_rate_" + TradingDayUtils.getDateStr(today) + ".csv";
        writeCsv(csvPath, csvLines);
        log.info("========== 涨停低吸高抛盈利概率统计完成，结果已写入 {} ==========", csvPath);
    }

    /** 计算胜率（%），样本数为 0 时返回 "无样本" */
    private String fmtRate(int total, int win)
    {
        if (total == 0)
        {
            return "无样本";
        }
        return BigDecimal.valueOf(win).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).toString();
    }

    /** 计算平均收益率（%），样本数为 0 时返回 "无样本" */
    private String fmtAvg(int total, BigDecimal returnSum)
    {
        if (total == 0)
        {
            return "无样本";
        }
        return returnSum.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).toString();
    }

    /** 计算胜率（%）用于 CSV，样本数为 0 时返回空字符串 */
    private String rateCsv(int total, int win)
    {
        if (total == 0)
        {
            return "";
        }
        return BigDecimal.valueOf(win).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 计算平均收益率（%）用于 CSV，样本数为 0 时返回空字符串 */
    private String avgCsv(int total, BigDecimal returnSum)
    {
        if (total == 0)
        {
            return "";
        }
        return returnSum.divide(BigDecimal.valueOf(total), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).toPlainString();
    }

    /** 拼接一行 CSV（自动处理逗号/引号转义） */
    private String csvLine(Object... fields)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++)
        {
            if (i > 0)
            {
                sb.append(',');
            }
            String f = fields[i] == null ? "" : String.valueOf(fields[i]);
            if (f.contains(",") || f.contains("\"") || f.contains("\n") || f.contains("\r"))
            {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            }
            else
            {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    /** 写入 CSV 文件（UTF-8 带 BOM，便于 Excel 打开） */
    private void writeCsv(String path, List<String> lines)
    {
        try
        {
            File file = new File(path);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists())
            {
                parent.mkdirs();
            }
            try (BufferedWriter w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
            {
                w.write('﻿');
                for (String line : lines)
                {
                    w.write(line);
                    w.newLine();
                }
            }
        }
        catch (IOException e)
        {
            log.error("写入 CSV 失败: {}", path, e);
        }
    }

    /**
     * 带参入口（参数预留，当前无参也走同一逻辑）
     */
    public void execute(String params)
    {
        execute();
    }

    /** Date -> LocalDate（按系统默认时区） */
    private LocalDate toLocalDate(Date d)
    {
        if (d == null)
        {
            return null;
        }
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** 构建 stock_code -> 板块/概念标签 映射（行业/概念 前缀区分） */
    private Map<String, List<String>> buildStockPlateMap(List<String> codes)
    {
        Map<String, List<String>> map = new HashMap<>();
        List<Map<String, Object>> rows = stockPlateMapper.selectPlateNamesByStockCodes(codes);
        if (rows == null)
        {
            return map;
        }
        for (Map<String, Object> row : rows)
        {
            String code = (String) row.get("stockCode");
            String name = (String) row.get("plateName");
            String type = (String) row.get("plateType");
            if (code == null || name == null)
            {
                continue;
            }
            String label = "CONCEPT".equals(type) ? "概念:" + name : "行业:" + name;
            map.computeIfAbsent(code, k -> new ArrayList<>()).add(label);
        }
        return map;
    }

    /** 板块/概念分类统计结果（A~G × 理论/实盘） */
    private static class PlateStat
    {
        final int[] theoTotals = new int[STRATEGY_COUNT];
        final int[] theoWins = new int[STRATEGY_COUNT];
        final BigDecimal[] theoReturnSums = new BigDecimal[STRATEGY_COUNT];
        final int[] realTotals = new int[STRATEGY_COUNT];
        final int[] realWins = new int[STRATEGY_COUNT];
        final BigDecimal[] realReturnSums = new BigDecimal[STRATEGY_COUNT];

        PlateStat()
        {
            Arrays.fill(theoReturnSums, BigDecimal.ZERO);
            Arrays.fill(realReturnSums, BigDecimal.ZERO);
        }
    }
}
