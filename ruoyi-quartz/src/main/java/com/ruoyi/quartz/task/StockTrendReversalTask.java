package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 趋势反转股票检测定时任务
 *
 * 检测近期趋势下跌、但最近2天上涨（且上影线长于下影线）的股票，
 * 创建以当前日期为名称的股票分组，将符合条件的股票关联到该分组。
 *
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockTrendReversalTask.detect()
 * 建议 cron: 0 0 16 * * ?（每个交易日下午4:00）
 *
 * @author ruoyi
 */
@Component("stockTrendReversalTask")
public class StockTrendReversalTask
{
    private static final Logger log = LoggerFactory.getLogger(StockTrendReversalTask.class);

    /** 最少需要的日K线记录数 */
    private static final int MIN_KLINES = 20;
    /** 从数据库拉取的日历天数（覆盖约20个交易日） */
    private static final int CALENDAR_LOOKBACK = 45;
    /** 近期上涨天数（最近2天连续上涨） */
    private static final int RECENT_RISE_DAYS = 2;
@Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /**
     * 无参调用
     */
    public void detect()
    {
        detect(null);
    }

    /**
     * 带参调用
     *
     * @param params 可指定最小K线记录数，如 "30"
     */
    public void detect(String params)
    {
        int minKlines = parseMinKlines(params);
        log.info("开始检测趋势反转股票，最少K线记录数={}", minKlines);

        try
        {
            // 1. 查询所有A股
            List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
            if (stocks == null || stocks.isEmpty())
            {
                log.info("无A股数据");
                return;
            }
            log.info("A股总数: {}", stocks.size());

            // 2. 批量查询日K线数据
            List<String> stockCodes = stocks.stream()
                    .map(Stock::getStockCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            String endDate = LocalDate.now().toString();
            String startDate = LocalDate.now().minusDays(CALENDAR_LOOKBACK).toString();

            List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(
                    stockCodes, "D", startDate, endDate);
            log.info("K线数据总量: {}", allKlines != null ? allKlines.size() : 0);

            if (allKlines == null || allKlines.isEmpty())
            {
                log.info("无K线数据，任务结束");
                return;
            }

            // 3. 按股票代码分组
            Map<String, List<StockKline>> klineMap = allKlines.stream()
                    .collect(Collectors.groupingBy(StockKline::getStockCode));

            // 4. 检测趋势反转
            List<Long> reversalStockIds = new ArrayList<>();
            for (Stock stock : stocks)
            {
                List<StockKline> klines = klineMap.get(stock.getStockCode());
                if (klines == null || klines.size() < minKlines)
                {
                    continue;
                }
                if (isTrendReversal(klines))
                {
                    reversalStockIds.add(stock.getId());
                }
            }

            log.info("检测到趋势反转股票数量: {}", reversalStockIds.size());

            if (reversalStockIds.isEmpty())
            {
                log.info("无符合条件的股票");
                return;
            }

            // 5. 创建分组并关联股票
            String groupName = "趋势反转_" + LocalDate.now().toString();
            StockGroup group = new StockGroup();
            group.setGroupName(groupName);
            group.setStockIds(reversalStockIds.toArray(new Long[0]));
            group.setCreateBy("system");

            stockGroupService.insertGroup(group);
            log.info("趋势反转检测完成: 分组=[{}], 股票数量={}", groupName, reversalStockIds.size());
        }
        catch (Exception e)
        {
            log.error("趋势反转检测任务执行异常", e);
        }
    }

    /**
     * 检测单只股票是否存在趋势反转信号
     *
     * 规则：
     * 1. 最近2天收盘价连续上涨
     * 2. 上涨日的上影线长于下影线（上影线 = 最高价 - max(开盘价,收盘价)，下影线 = min(开盘价,收盘价) - 最低价）
     * 3. 前期（最近上涨之前）收盘价线性回归斜率为负，即整体处于下跌趋势
     *
     * @param klines 按trade_time升序排列的日K线列表
     * @return true=存在趋势反转信号
     */
    private boolean isTrendReversal(List<StockKline> klines)
    {
        int n = klines.size();

        // ---- 规则1: 最近2天收盘价连续上涨 ----
        for (int i = n - RECENT_RISE_DAYS; i < n - 1; i++)
        {
            BigDecimal today = klines.get(i).getClosePrice();
            BigDecimal next = klines.get(i + 1).getClosePrice();
            if (today == null || next == null || today.compareTo(next) >= 0)
            {
                return false;
            }
        }

        // ---- 规则2: 上涨日上影线 > 下影线 ----
        for (int i = n - RECENT_RISE_DAYS; i < n; i++)
        {
            StockKline k = klines.get(i);
            BigDecimal open = k.getOpenPrice();
            BigDecimal close = k.getClosePrice();
            BigDecimal high = k.getHighPrice();
            BigDecimal low = k.getLowPrice();

            if (open == null || close == null || high == null || low == null)
            {
                return false;
            }

            // 上影线 = 最高价 - max(开盘价, 收盘价)
            BigDecimal upperShadow = high.subtract(open.max(close));
            // 下影线 = min(开盘价, 收盘价) - 最低价
            BigDecimal lowerShadow = open.min(close).subtract(low);

            if (upperShadow.compareTo(lowerShadow) <= 0)
            {
                return false;
            }
        }

        // ---- 规则3: 前期线性回归斜率 < 0（下跌趋势） ----
        int priorEnd = n - RECENT_RISE_DAYS;
        if (priorEnd < 5)
        {
            return false; // 前期样本不足，无法判定趋势
        }

        // 构建 (x, y) 序列：x = 索引, y = 收盘价
        int m = priorEnd;
        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXY = BigDecimal.ZERO;
        BigDecimal sumX2 = BigDecimal.ZERO;

        for (int i = 0; i < m; i++)
        {
            BigDecimal close = klines.get(i).getClosePrice();
            if (close == null) return false;

            BigDecimal xi = BigDecimal.valueOf(i);
            sumX = sumX.add(xi);
            sumY = sumY.add(close);
            sumXY = sumXY.add(xi.multiply(close));
            sumX2 = sumX2.add(xi.multiply(xi));
        }

        // slope = (m * Σxy - Σx * Σy) / (m * Σx² - (Σx)²)
        BigDecimal mM = BigDecimal.valueOf(m);
        BigDecimal numerator = mM.multiply(sumXY).subtract(sumX.multiply(sumY));
        BigDecimal denominator = mM.multiply(sumX2).subtract(sumX.multiply(sumX));

        if (denominator.compareTo(BigDecimal.ZERO) == 0)
        {
            return false;
        }

        // 斜率为负 → 下跌趋势
        BigDecimal slope = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
        return slope.compareTo(BigDecimal.ZERO) < 0;
    }

    /**
     * 解析参数为最小K线记录数
     */
    private int parseMinKlines(String params)
    {
        if (params != null && !params.trim().isEmpty())
        {
            try
            {
                int val = Integer.parseInt(params.trim());
                if (val >= 10)
                {
                    return val;
                }
                log.warn("最小K线数需≥10: {}, 使用默认值{}", val, MIN_KLINES);
            }
            catch (NumberFormatException e)
            {
                log.warn("参数解析失败: {}, 使用默认值{}", params, MIN_KLINES);
            }
        }
        return MIN_KLINES;
    }
}
