package com.ruoyi.quartz.task;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
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
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 涨停低吸高抛参数优化定时任务
 *
 * 用最近 lookbackDays 天的涨停股做实验，网格搜索「可执行低吸高抛」的两个参数：
 *   买入折扣 buyDiscount：买入日开盘价下方挂单比例（低吸）；
 *   卖出目标 sellTarget：买入价上方挂单比例（高抛）。
 * 统计每个 (buyDiscount, sellTarget) 组合下，策略A（T+1买入 / T+2卖出）的胜率，找出胜率最高的组合。
 *
 * 输出：日志打印胜率最高的若干组合；完整网格写入 CSV。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockLimitUpOptimizeTask.execute()
 */
@Component("stockLimitUpOptimizeTask")
public class StockLimitUpOptimizeTask
{
    private static final Logger log = LoggerFactory.getLogger(StockLimitUpOptimizeTask.class);

    /** 回看天数（用最近 N 天的涨停事件做实验） */
    @Value("${stock.limit-up-optimize.lookback-days:60}")
    private int lookbackDays;

    /** 买入折扣网格：最小值 */
    @Value("${stock.limit-up-optimize.buy-discount-min:0.00}")
    private BigDecimal buyDiscountMin;

    /** 买入折扣网格：最大值 */
    @Value("${stock.limit-up-optimize.buy-discount-max:0.10}")
    private BigDecimal buyDiscountMax;

    /** 买入折扣网格：步长 */
    @Value("${stock.limit-up-optimize.buy-discount-step:0.01}")
    private BigDecimal buyDiscountStep;

    /** 卖出目标网格：最小值 */
    @Value("${stock.limit-up-optimize.sell-target-min:0.00}")
    private BigDecimal sellTargetMin;

    /** 卖出目标网格：最大值 */
    @Value("${stock.limit-up-optimize.sell-target-max:0.10}")
    private BigDecimal sellTargetMax;

    /** 卖出目标网格：步长 */
    @Value("${stock.limit-up-optimize.sell-target-step:0.01}")
    private BigDecimal sellTargetStep;

    /** 结果输出目录 */
    @Value("${stock.analysis.output-dir:./data}")
    private String outputDir;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    public void execute()
    {
        log.info("========== 涨停低吸高抛参数优化开始 ==========");

        LocalDate today = LocalDate.now();
        String startDate = TradingDayUtils.getDateStr(today.minusDays(lookbackDays));
        String endDate = TradingDayUtils.getDateStr(today);

        // 1. 查询涨停事件
        List<StockLimitUpRecord> events = stockLimitUpRecordMapper.selectLimitUpEvents(startDate, endDate);
        if (events == null || events.isEmpty())
        {
            log.warn("最近 {} 天无涨停记录，跳过优化", lookbackDays);
            return;
        }
        log.info("涨停事件（去重后）共 {} 条", events.size());

        // 2. 查询日K线
        List<String> codes = events.stream()
                .map(StockLimitUpRecord::getSecuCode)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (codes.isEmpty())
        {
            log.warn("无有效股票代码，跳过优化");
            return;
        }
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

        // 3. 构建参数网格
        BigDecimal[] buyDiscounts = range(buyDiscountMin, buyDiscountMax, buyDiscountStep);
        BigDecimal[] sellTargets = range(sellTargetMin, sellTargetMax, sellTargetStep);
        int nBuy = buyDiscounts.length;
        int nSell = sellTargets.length;
        int[][] wins = new int[nBuy][nSell];
        int total = 0;

        // 4. 遍历涨停事件，统计每个参数组合的胜率（策略A：T+1买入 / T+2卖出）
        for (StockLimitUpRecord event : events)
        {
            String code = event.getSecuCode();
            LocalDate t = toLocalDate(event.getTradeDate());
            if (code == null || t == null)
            {
                continue;
            }
            List<StockKline> klines = klineMap.get(code);
            if (klines == null)
            {
                continue;
            }

            // 定位 T+1（买入日）、T+2（卖出日）
            StockKline t1 = null;
            StockKline t2 = null;
            for (StockKline k : klines)
            {
                LocalDate kd = toLocalDate(k.getTradeTime());
                if (kd == null || !kd.isAfter(t))
                {
                    continue;
                }
                if (t1 == null)
                {
                    t1 = k;
                }
                else
                {
                    t2 = k;
                    break;
                }
            }
            if (t1 == null || t2 == null)
            {
                continue;
            }

            BigDecimal buyOpen = t1.getOpenPrice();
            BigDecimal buyClose = t1.getClosePrice();
            BigDecimal buyLow = t1.getLowPrice();
            BigDecimal sellHigh = t2.getHighPrice();
            BigDecimal sellClose = t2.getClosePrice();
            if (buyOpen == null || buyClose == null || buyLow == null || sellHigh == null || sellClose == null
                    || buyOpen.compareTo(BigDecimal.ZERO) <= 0 || buyClose.compareTo(BigDecimal.ZERO) <= 0
                    || sellClose.compareTo(BigDecimal.ZERO) <= 0)
            {
                continue;
            }

            total++;

            for (int i = 0; i < nBuy; i++)
            {
                // 买入：开盘价下方 buyDiscount 挂单，跌到成交，否则收盘买
                BigDecimal buyLimit = buyOpen.multiply(BigDecimal.ONE.subtract(buyDiscounts[i]));
                BigDecimal buy = buyLow.compareTo(buyLimit) <= 0 ? buyLimit : buyClose;

                for (int j = 0; j < nSell; j++)
                {
                    // 卖出：买入价上方 sellTarget 挂单，涨到成交，否则收盘卖
                    BigDecimal sellTarget = buy.multiply(BigDecimal.ONE.add(sellTargets[j]));
                    BigDecimal sell = sellHigh.compareTo(sellTarget) >= 0 ? sellTarget : sellClose;
                    if (sell.compareTo(buy) > 0)
                    {
                        wins[i][j]++;
                    }
                }
            }
        }

        if (total == 0)
        {
            log.warn("无有效样本，无法优化");
            return;
        }
        log.info("有效样本 {} 条，参数网格 {} x {}", total, nBuy, nSell);

        // 5. 找出胜率最高的组合
        List<Result> results = new ArrayList<>();
        for (int i = 0; i < nBuy; i++)
        {
            for (int j = 0; j < nSell; j++)
            {
                results.add(new Result(buyDiscounts[i], sellTargets[j], wins[i][j], total));
            }
        }
        results.sort(Comparator.comparingDouble(Result::getWinRate).reversed());

        log.info("========== 胜率最高的 10 个参数组合 ==========");
        for (int k = 0; k < Math.min(10, results.size()); k++)
        {
            Result r = results.get(k);
            log.info("买入折扣 {}%，卖出目标 {}%：胜率 {}%",
                    r.buyDiscount.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    r.sellTarget.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    r.winRate());
        }

        // 6. 写 CSV
        List<String> csvLines = new ArrayList<>();
        csvLines.add(csvLine("买入折扣%", "卖出目标%", "样本数", "盈利次数", "胜率%"));
        for (Result r : results)
        {
            csvLines.add(csvLine(
                    r.buyDiscount.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    r.sellTarget.multiply(BigDecimal.valueOf(100)).toPlainString(),
                    total, r.win, r.winRate()));
        }
        String csvPath = outputDir + "/limit_up_optimize_" + TradingDayUtils.getDateStr(today) + ".csv";
        writeCsv(csvPath, csvLines);

        log.info("========== 涨停低吸高抛参数优化完成，结果已写入 {} ==========", csvPath);
    }

    /** 生成 [min, max] 步长 step 的数值网格 */
    private BigDecimal[] range(BigDecimal min, BigDecimal max, BigDecimal step)
    {
        if (step == null || step.compareTo(BigDecimal.ZERO) <= 0)
        {
            return new BigDecimal[]{min};
        }
        List<BigDecimal> list = new ArrayList<>();
        for (BigDecimal v = min; v.compareTo(max) <= 0; v = v.add(step))
        {
            list.add(v);
        }
        return list.toArray(new BigDecimal[0]);
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

    /** 参数组合结果 */
    private static class Result
    {
        final BigDecimal buyDiscount;
        final BigDecimal sellTarget;
        final int win;
        final int total;

        Result(BigDecimal buyDiscount, BigDecimal sellTarget, int win, int total)
        {
            this.buyDiscount = buyDiscount;
            this.sellTarget = sellTarget;
            this.win = win;
            this.total = total;
        }

        double getWinRate()
        {
            return total == 0 ? 0 : (double) win / total;
        }

        String winRate()
        {
            if (total == 0)
            {
                return "";
            }
            return BigDecimal.valueOf(win).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP).toPlainString();
        }
    }
}
