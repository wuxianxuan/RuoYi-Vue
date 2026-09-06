package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockDeclineReversalService;
import com.ruoyi.system.service.IStockGroupService;
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
 * 下跌反转选股实现
 *
 * 算法流程（对每只股票）：
 *   1. 取最近 lookbackDays 个日历日内的日K线，按交易日升序；
 *   2. 判断最近一天收盘价是否为 a 天内最低；
 *   3. 若是，判断最近一天是否为「缩量下跌」：
 *      - 下跌：当日收盘价 < 前一交易日收盘价；
 *      - 缩量：当日成交量 < 前一交易日成交量。
 *
 * 命中股票生成一个新分组。
 *
 * @author ruoyi
 */
@Service
public class StockDeclineReversalServiceImpl implements IStockDeclineReversalService
{
    private static final Logger log = LoggerFactory.getLogger(StockDeclineReversalServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** 回看日历天数（a） */
    @Value("${stock.decline-reversal.lookback-days:60}")
    private int lookbackDays;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.decline-reversal.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.decline-reversal.markets:SH,SZ,BJ}")
    private String markets;

    @Override
    public void execute()
    {
        log.info("========== 下跌反转选股开始 ==========");
        log.info("参数：lookbackDays={} excludeSt={} markets={}", lookbackDays, excludeSt, markets);

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
        log.info("========== 下跌反转选股完成 ==========");
    }

    /**
     * 判定单只股票是否满足「a日内收盘新低 + 缩量下跌」
     */
    private boolean matches(List<StockKline> klines)
    {
        if (klines == null || klines.isEmpty())
        {
            return false;
        }

        // 按交易日升序排序，提取有效收盘价与成交量
        List<StockKline> list = new ArrayList<>(klines);
        list.sort(Comparator.comparing(StockKline::getTradeTime));

        List<Double> closes = new ArrayList<>();
        List<Double> vols = new ArrayList<>();
        for (StockKline kl : list)
        {
            if (kl.getClosePrice() != null)
            {
                closes.add(kl.getClosePrice().doubleValue());
                vols.add(kl.getVolume() == null ? 0d : kl.getVolume().doubleValue());
            }
        }
        if (closes.size() < 2)
        {
            return false;
        }

        int last = closes.size() - 1;
        double lastClose = closes.get(last);

        // 1. 最近一天是 a 天内最低的收盘价（不大于此前任意一天）
        for (int i = 0; i < last; i++)
        {
            if (closes.get(i) < lastClose)
            {
                return false;
            }
        }

        // 2. 下跌：当日收盘价较前一交易日下跌
        if (!(lastClose < closes.get(last - 1)))
        {
            return false;
        }

        // 3. 缩量：当日成交量较前一交易日缩量
        if (!(vols.get(last) < vols.get(last - 1)))
        {
            return false;
        }

        return true;
    }

    /**
     * 生成下跌反转分组
     */
    private void createGroup(List<Stock> matched)
    {
        String groupName = "下跌反转选股_" + LocalDate.now().toString();
        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockCodes(matched.stream().map(Stock::getStockCode).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("最近%d日内收盘新低且缩量下跌（命中%d只）", lookbackDays, matched.size()));

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
