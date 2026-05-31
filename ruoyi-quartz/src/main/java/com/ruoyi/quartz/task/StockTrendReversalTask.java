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
 * 检测最近一段时间先下跌、最近2-3天上涨的股票（由跌转涨的趋势反转信号），
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
    /** 近期上涨检查的天数（最近N天连续上涨） */
    private static final int RECENT_RISE_DAYS = 3;
    /** 反弹幅度阈值：当前收盘价需高于近期最低价的百分比 */
    private static final BigDecimal REBOUND_THRESHOLD = new BigDecimal("1.02");
    /** 下跌幅度阈值：前期高点到低点至少跌多少百分比 */
    private static final BigDecimal DECLINE_THRESHOLD = new BigDecimal("0.97");

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
     * 1. 最近N天收盘价连续上涨
     * 2. 前期存在明显的下跌趋势（高点→低点跌幅 ≥ 3%）
     * 3. 前期低点发生在高点之后（确认先跌后涨的时间顺序）
     * 4. 当前收盘价相比前期低点有一定反弹幅度（≥ 2%）
     *
     * @param klines 按trade_time升序排列的日K线列表
     * @return true=存在趋势反转信号
     */
    private boolean isTrendReversal(List<StockKline> klines)
    {
        int n = klines.size();

        // ---- 规则1: 最近N天收盘价连续上涨 ----
        for (int i = n - RECENT_RISE_DAYS; i < n - 1; i++)
        {
            BigDecimal today = klines.get(i).getClosePrice();
            BigDecimal next = klines.get(i + 1).getClosePrice();
            if (today == null || next == null || today.compareTo(next) >= 0)
            {
                return false;
            }
        }

        // 当前最新收盘价
        BigDecimal currentClose = klines.get(n - 1).getClosePrice();
        if (currentClose == null) return false;

        // ---- 规则2: 前期存在下跌（在最近上涨之前的区间 [0, n-RECENT_RISE_DAYS) 内） ----
        int priorEnd = n - RECENT_RISE_DAYS; // 前期区间的结束位置（不含）

        BigDecimal maxClose = null;
        int maxIndex = -1;
        BigDecimal minClose = null;
        int minIndex = -1;

        for (int i = 0; i < priorEnd; i++)
        {
            BigDecimal close = klines.get(i).getClosePrice();
            if (close == null) continue;
            if (maxClose == null || close.compareTo(maxClose) > 0)
            {
                maxClose = close;
                maxIndex = i;
            }
            if (minClose == null || close.compareTo(minClose) < 0)
            {
                minClose = close;
                minIndex = i;
            }
        }

        if (maxClose == null || minClose == null || maxIndex < 0 || minIndex < 0)
        {
            return false;
        }

        // ---- 规则3: 低点发生在高点之后（先跌后涨的时间顺序） ----
        if (minIndex <= maxIndex)
        {
            return false;
        }

        // ---- 规则4: 跌幅足够大（高点→低点跌幅 ≥ 3%） ----
        BigDecimal declineRatio = minClose.divide(maxClose, 4, RoundingMode.HALF_UP);
        if (declineRatio.compareTo(DECLINE_THRESHOLD) > 0)
        {
            // 跌幅不足3%
            return false;
        }

        // ---- 规则5: 当前价相比低点有反弹（≥ 2%） ----
        if (currentClose.compareTo(minClose.multiply(REBOUND_THRESHOLD)) < 0)
        {
            return false;
        }

        return true;
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
