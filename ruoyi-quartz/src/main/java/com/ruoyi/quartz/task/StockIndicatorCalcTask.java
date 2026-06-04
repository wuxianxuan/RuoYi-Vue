package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockIndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 技术指标计算定时任务
 *
 * 依赖：stock_kline 已有数据
 * 建议放在 StockKlineSyncTask 之后执行
 */
@Component("stockIndicatorCalcTask")
public class StockIndicatorCalcTask
{
    private static final Logger log = LoggerFactory.getLogger(StockIndicatorCalcTask.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockIndicatorService stockIndicatorService;

    /** K线回溯天数（多取20天buffer确保MA60有足够数据） */
    private static final int KLINES_LOOKBACK = 80;

    /** 每批持久化的记录数 */
    private static final int BATCH_SIZE = 500;

    /**
     * 无参入口
     */
    public void calc()
    {
        calc(null);
    }

    /**
     * 带参入口
     *
     * @param params 可选：指定股票代码（逗号分隔），为空则处理全量
     */
    public void calc(String params)
    {
        long start = System.currentTimeMillis();
        log.info("========== 技术指标计算开始 ==========");

        try
        {
            // 1. 获取股票列表
            List<Stock> stocks;
            if (params != null && !params.trim().isEmpty())
            {
                // 指定股票
                String[] codes = params.split(",");
                stocks = new ArrayList<>();
                for (String code : codes)
                {
                    Stock s = new Stock();
                    s.setStockCode(code.trim());
                    stocks.add(s);
                }
            }
            else
            {
                // 全量 SH/SZ 股票
                stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
            }

            if (stocks == null || stocks.isEmpty())
            {
                log.warn("未找到股票记录");
                return;
            }
            log.info("共 {} 只股票待处理", stocks.size());

            // 2. 批量获取K线数据
            List<String> stockCodes = stocks.stream()
                    .map(Stock::getStockCode)
                    .filter(c -> c != null && !c.isEmpty())
                    .collect(Collectors.toList());

            String endDate = LocalDate.now().toString();
            String startDate = LocalDate.now().minusDays(KLINES_LOOKBACK).toString();

            List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(
                    stockCodes, "D", startDate, endDate);

            if (allKlines == null || allKlines.isEmpty())
            {
                log.warn("未获取到K线数据");
                return;
            }
            log.info("获取到 {} 条K线数据", allKlines.size());

            // 3. 按股票代码分组
            Map<String, List<StockKline>> grouped = allKlines.stream()
                    .collect(Collectors.groupingBy(StockKline::getStockCode));

            // 4. 逐股计算指标
            int totalSaved = 0;
            int skipCount = 0;
            List<StockIndicator> batchBuffer = new ArrayList<>(BATCH_SIZE);

            for (String code : stockCodes)
            {
                List<StockKline> klines = grouped.get(code);
                if (klines == null || klines.size() < 20)
                {
                    skipCount++;
                    continue;
                }

                // 按交易时间升序排列
                klines.sort(Comparator.comparing(StockKline::getTradeTime));

                // 只取最近 KLINES_LOOKBACK 条（如果有更多）
                List<StockKline> recent = klines.size() > KLINES_LOOKBACK
                        ? klines.subList(klines.size() - KLINES_LOOKBACK, klines.size())
                        : klines;

                try
                {
                    List<StockIndicator> indicators = stockIndicatorService.calcIndicators(recent);
                    if (indicators != null && !indicators.isEmpty())
                    {
                        batchBuffer.addAll(indicators);

                        // 分批刷入数据库
                        if (batchBuffer.size() >= BATCH_SIZE)
                        {
                            totalSaved += stockIndicatorService.batchSaveIndicators(
                                    new ArrayList<>(batchBuffer));
                            batchBuffer.clear();
                        }
                    }
                }
                catch (Exception e)
                {
                    log.warn("计算指标失败: stockCode={}, error={}", code, e.getMessage());
                }
            }

            // 刷入剩余数据
            if (!batchBuffer.isEmpty())
            {
                totalSaved += stockIndicatorService.batchSaveIndicators(batchBuffer);
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("========== 技术指标计算完成：共保存 {} 条记录，跳过 {} 只股票（K线不足），耗时 {} ms ==========",
                    totalSaved, skipCount, elapsed);
        }
        catch (Exception e)
        {
            log.error("技术指标计算异常", e);
        }
    }
}
