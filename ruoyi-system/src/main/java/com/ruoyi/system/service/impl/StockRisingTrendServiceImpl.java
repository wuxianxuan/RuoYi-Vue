package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 趋势上涨选股实现
 *
 * 算法流程（对每只股票）：
 *   1. 取最近 lookbackDays 个日历日内的日K线；
 *   2. 按交易日升序，找到「收盘价最低」的那天作为起点，切片 [最低日, 最新日]；
 *   3. 每 pointsPerNode 个收盘价取均值整合成一个节点，形成节点链；
 *   4. 判定节点链是否逐步上涨：任意连续 windowNodes 个节点的窗口内，
 *      节点相对前一节点下跌的次数必须 < maxDeviation（允许偶尔偏差）。
 *
 * 命中股票生成一个新分组。
 *
 * @author ruoyi
 */
@Service
public class StockRisingTrendServiceImpl implements IStockRisingTrendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRisingTrendServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** 回看日历天数（a） */
    @Value("${stock.rising-trend.lookback-days:60}")
    private int lookbackDays;

    /** 每个节点整合的K线点数（b） */
    @Value("${stock.rising-trend.points-per-node:3}")
    private int pointsPerNode;

    /** 偏离判定的滑动窗口大小，每 x 个节点（x） */
    @Value("${stock.rising-trend.window-nodes:10}")
    private int windowNodes;

    /** 每个窗口内允许的最大偏离（下跌）次数（y，偏离次数必须 < 该值） */
    @Value("${stock.rising-trend.max-deviation:2}")
    private int maxDeviation;

    /** 最少节点数，低于该值不参与判定 */
    @Value("${stock.rising-trend.min-nodes:5}")
    private int minNodes;

    /** 是否要求整体上涨（末节点 > 首节点） */
    @Value("${stock.rising-trend.require-overall-up:true}")
    private boolean requireOverallUp;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.rising-trend.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.rising-trend.markets:SH,SZ,BJ}")
    private String markets;

    @Override
    public void execute()
    {
        log.info("========== 趋势上涨选股开始 ==========");
        log.info("参数：lookbackDays={} pointsPerNode={} windowNodes={} maxDeviation={} minNodes={} requireOverallUp={} excludeSt={} markets={}",
                lookbackDays, pointsPerNode, windowNodes, maxDeviation, minNodes, requireOverallUp, excludeSt, markets);

        // 1. 查询候选股票基础信息
        List<Stock> stocks = stockMapper.selectStocksByMarkets(parseMarkets());
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("指定市场下无股票，跳过选股");
            return;
        }

        Map<String, Stock> stockMap = new LinkedHashMap<>();
        for (Stock s : stocks)
        {
            if (s.getStockCode() == null)
            {
                continue;
            }
            if (excludeSt && s.getStockName() != null && s.getStockName().toUpperCase().contains("ST"))
            {
                continue;
            }
            stockMap.put(s.getStockCode(), s);
        }
        if (stockMap.isEmpty())
        {
            log.warn("过滤后无候选股票，跳过选股");
            return;
        }
        log.info("候选股票 {} 只（已排除 ST {} 只）", stockMap.size(), stocks.size() - stockMap.size());

        // 2. 批量拉取日K线
        String endDate = LocalDate.now().toString();
        String startDate = LocalDate.now().minusDays(lookbackDays).toString();
        List<String> codes = new ArrayList<>(stockMap.keySet());
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate, endDate);
        log.info("K线数据总量: {}", allKlines != null ? allKlines.size() : 0);

        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));

        // 3. 逐只判定
        List<Stock> matched = new ArrayList<>();
        for (Stock s : stockMap.values())
        {
            if (matches(klineMap.get(s.getStockCode())))
            {
                matched.add(s);
            }
        }
        log.info("符合条件的股票 {} 只", matched.size());

        // 4. 生成分组（无命中则不生成）
        if (matched.isEmpty())
        {
            log.warn("无符合条件的股票，任务结束");
            return;
        }

        createGroup(matched);
        log.info("========== 趋势上涨选股完成 ==========");
    }

    /**
     * 判定单只股票是否满足「逐步上涨」形态
     */
    private boolean matches(List<StockKline> klines)
    {
        if (klines == null || klines.isEmpty())
        {
            return false;
        }

        // 按交易日升序排序，提取有效收盘价
        List<StockKline> list = new ArrayList<>(klines);
        list.sort(Comparator.comparing(StockKline::getTradeTime));

        List<Double> closes = new ArrayList<>();
        for (StockKline kl : list)
        {
            if (kl.getClosePrice() != null)
            {
                closes.add(kl.getClosePrice().doubleValue());
            }
        }
        if (closes.size() < 2)
        {
            return false;
        }

        // 找到收盘价最低的那天，作为起点切片到最新
        int minIdx = 0;
        for (int i = 1; i < closes.size(); i++)
        {
            if (closes.get(i) < closes.get(minIdx))
            {
                minIdx = i;
            }
        }
        if (minIdx >= closes.size() - 1)
        {
            // 最低点就是最新一天，无上升空间
            return false;
        }
        List<Double> seg = closes.subList(minIdx, closes.size());

        // 每 pointsPerNode 个点取均值整合成一个节点（忽略末尾不足一组的点）
        List<Double> nodes = new ArrayList<>();
        for (int i = 0; i + pointsPerNode <= seg.size(); i += pointsPerNode)
        {
            double sum = 0;
            for (int k = i; k < i + pointsPerNode; k++)
            {
                sum += seg.get(k);
            }
            nodes.add(sum / pointsPerNode);
        }

        int n = nodes.size();
        if (n < minNodes)
        {
            return false;
        }

        // 整体上涨约束：末节点 > 首节点
        if (requireOverallUp && nodes.get(n - 1) <= nodes.get(0))
        {
            return false;
        }

        // 偏离判定：节点相对前一节点下跌算一次偏离
        if (n >= windowNodes)
        {
            // 滑动窗口：任意连续 windowNodes 个节点内，下跌次数必须 < maxDeviation
            for (int j = 0; j + windowNodes <= n; j++)
            {
                int dev = 0;
                for (int i = j + 1; i < j + windowNodes; i++)
                {
                    if (nodes.get(i) < nodes.get(i - 1))
                    {
                        dev++;
                    }
                }
                if (dev >= maxDeviation)
                {
                    return false;
                }
            }
        }
        else
        {
            // 节点数不足一个窗口时退化为全局下跌计数
            int dev = 0;
            for (int i = 1; i < n; i++)
            {
                if (nodes.get(i) < nodes.get(i - 1))
                {
                    dev++;
                }
            }
            if (dev >= maxDeviation)
            {
                return false;
            }
        }

        return true;
    }

    /**
     * 生成趋势上涨分组
     */
    private void createGroup(List<Stock> matched)
    {
        String groupName = "趋势上涨选股_" + LocalDate.now().toString();
        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockCodes(matched.stream().map(Stock::getStockCode).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("最近%d日自收盘最低点上涨，每%d点成节点，每%d节点偏离<%d（命中%d只）",
                lookbackDays, pointsPerNode, windowNodes, maxDeviation, matched.size()));

        stockGroupService.insertGroup(group);

        log.info("分组 [{}] 命中 {} 只", groupName, matched.size());
        for (Stock s : matched)
        {
            log.info("    {} {}", s.getStockCode(), s.getStockName());
        }
    }

    /**
     * 解析市场列表配置
     */
    private List<String> parseMarkets()
    {
        if (markets == null || markets.trim().isEmpty())
        {
            return Arrays.asList("SH", "SZ", "BJ");
        }
        return Arrays.stream(markets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
