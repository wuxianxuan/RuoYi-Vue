package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockRisingLowMapper;
import com.ruoyi.system.service.IStockDayTradingService;
import com.ruoyi.system.service.IStockGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 趋势积累池减法 + 日推荐实现
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法并产出当日推荐：
 *   1. 逐只比较「当天收盘价」与「入积累池后最近一个最低日期的收盘价」，若更低说明股价走下坡，移出积累池；
 *   2. 对未走下坡的股票做最近趋势过滤（任一命中即不进日推荐）：
 *      - 最近两个交易日都下跌；
 *      - 当前股价低于前天开盘价；
 *   3. 剩余股票生成当天日推荐分组（分组名 = 积累池分组名 + "_" + 日期）。
 *
 * @author ruoyi
 */
@Service
public class StockDayTradingServiceImpl implements IStockDayTradingService
{
    private static final Logger log = LoggerFactory.getLogger(StockDayTradingServiceImpl.class);

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockRisingLowMapper stockRisingLowMapper;

    /** 目标趋势积累分组名（与趋势上涨选股任务保持一致） */
    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String targetGroupName;

    /** 最近趋势回看日历天数（用于拉取近几日的日K线） */
    @Value("${stock.day-trading.lookback-days:7}")
    private int dayTradingLookbackDays;

    @Override
    public void execute()
    {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(dayTradingLookbackDays);
        log.info("========== 趋势积累池减法 + 日推荐开始 ==========");
        log.info("参数：targetGroupName={} dayTradingLookbackDays={} endDate={} startDate={}",
                targetGroupName, dayTradingLookbackDays, endDate, startDate);

        // 1. 查找趋势积累分组
        StockGroup group = findGroupByName(targetGroupName);
        if (group == null)
        {
            log.warn("未找到目标分组 [{}]，跳过", targetGroupName);
            return;
        }

        // 2. 取分组内现有股票
        List<Stock> poolStocks = stockGroupService.selectStocksByGroupId(group.getId());
        if (poolStocks == null || poolStocks.isEmpty())
        {
            log.warn("分组 [{}] 内无股票，跳过", targetGroupName);
            return;
        }
        List<String> codes = poolStocks.stream()
                .map(Stock::getStockCode)
                .filter(code -> code != null)
                .distinct()
                .collect(Collectors.toList());
        log.info("分组 [{}] 当前 {} 只，开始复查", targetGroupName, codes.size());

        // 3. 查询每只股票最近一个最低日及其收盘价
        Map<String, Double> lowCloseMap = new LinkedHashMap<>();
        List<Map<String, Object>> lowRecords = stockRisingLowMapper.selectLatestLowWithClose(codes);
        if (lowRecords != null)
        {
            for (Map<String, Object> record : lowRecords)
            {
                Object code = record.get("stockCode");
                Object close = record.get("closePrice");
                if (code != null && close != null)
                {
                    lowCloseMap.put(code.toString(), ((Number) close).doubleValue());
                }
            }
        }
        log.info("有最低点记录的股票 {} 只", lowCloseMap.size());

        // 4. 批量拉取最近日K线
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate.toString(), endDate.toString());
        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream()
                        .filter(k -> k.getStockCode() != null)
                        .collect(Collectors.groupingBy(StockKline::getStockCode));

        // 5. 逐只判定：走下坡移除 + 趋势过滤
        List<String> removeCodes = new ArrayList<>();
        List<String> passCodes = new ArrayList<>();
        for (String code : codes)
        {
            List<StockKline> klines = klineMap.get(code);
            if (klines == null || klines.isEmpty())
            {
                continue;
            }
            klines.sort(Comparator.comparing(StockKline::getTradeTime));

            Double lastClose = closeOf(klines.get(klines.size() - 1));

            // 5.1 走下坡：当天收盘价低于最近一个最低日收盘价 → 移出积累池
            Double lowClose = lowCloseMap.get(code);
            if (lastClose != null && lowClose != null && lastClose < lowClose)
            {
                removeCodes.add(code);
                log.info("    走下坡移除 {}：当天收盘 {} 低于最低日收盘 {}", code, lastClose, lowClose);
                continue;
            }

            // 5.2 最近趋势过滤：最近两日连续下跌，或当前价低于前天开盘价
            if (isRecentWeak(klines))
            {
                continue;
            }

            passCodes.add(code);
        }

        // 6. 移除走下坡股票
        if (!removeCodes.isEmpty())
        {
            stockGroupService.deleteGroupStocks(group.getId(), removeCodes.toArray(new String[0]));
            log.info("从分组 [{}] 移除走下坡 {} 只，剩余 {} 只",
                    targetGroupName, removeCodes.size(), codes.size() - removeCodes.size());
        }
        else
        {
            log.info("分组 [{}] 无走下坡股票", targetGroupName);
        }

        // 7. 生成日推荐分组
        if (passCodes.isEmpty())
        {
            log.warn("无通过趋势过滤的股票，不生成日推荐分组");
            return;
        }

        createDailyGroup(passCodes, endDate);
        log.info("========== 趋势积累池减法 + 日推荐完成 ==========");
    }

    /**
     * 最近趋势过滤：最近两个交易日都下跌，或当前股价低于前天开盘价，任一命中即过滤
     */
    private boolean isRecentWeak(List<StockKline> klines)
    {
        int n = klines.size();
        if (n < 3)
        {
            // 数据不足，无法判断趋势，保守过滤
            return true;
        }

        StockKline last = klines.get(n - 1);
        StockKline prev = klines.get(n - 2);
        StockKline prev2 = klines.get(n - 3);

        Double lastClose = closeOf(last);
        Double prevClose = closeOf(prev);
        Double prev2Close = closeOf(prev2);
        Double prev2Open = openOf(prev2);

        // ① 最近两个交易日都下跌
        if (lastClose != null && prevClose != null && prev2Close != null
                && lastClose < prevClose && prevClose < prev2Close)
        {
            return true;
        }

        // ② 当前股价低于前天开盘价
        if (lastClose != null && prev2Open != null && lastClose < prev2Open)
        {
            return true;
        }

        return false;
    }

    /**
     * 生成当天日推荐分组（分组名含日期，幂等：同名已存在则跳过）
     */
    private void createDailyGroup(List<String> passCodes, LocalDate endDate)
    {
        String dailyGroupName = targetGroupName + "_" + endDate;

        // 幂等：同名分组已存在则跳过
        StockGroup query = new StockGroup();
        query.setGroupName(dailyGroupName);
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups != null)
        {
            for (StockGroup g : groups)
            {
                if (dailyGroupName.equals(g.getGroupName()))
                {
                    log.info("日推荐分组 [{}] 已存在，跳过", dailyGroupName);
                    return;
                }
            }
        }

        StockGroup group = new StockGroup();
        group.setGroupName(dailyGroupName);
        group.setStockCodes(passCodes.toArray(new String[0]));
        group.setCreateBy("system");
        group.setRemark(String.format("%s（截止%s自积累池[%s]筛选，未走下坡且最近趋势未走弱，当日命中%d只）",
                dailyGroupName, endDate, targetGroupName, passCodes.size()));
        stockGroupService.insertGroup(group);

        log.info("生成日推荐分组 [{}]，命中 {} 只", dailyGroupName, passCodes.size());
        for (String code : passCodes)
        {
            log.info("    {}", code);
        }
    }

    /**
     * 按精确名称查找分组，找不到返回 null
     */
    private StockGroup findGroupByName(String name)
    {
        StockGroup query = new StockGroup();
        query.setGroupName(name);
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups != null)
        {
            for (StockGroup g : groups)
            {
                if (name.equals(g.getGroupName()))
                {
                    return g;
                }
            }
        }
        return null;
    }

    private Double closeOf(StockKline kline)
    {
        return kline.getClosePrice() == null ? null : kline.getClosePrice().doubleValue();
    }

    private Double openOf(StockKline kline)
    {
        return kline.getOpenPrice() == null ? null : kline.getOpenPrice().doubleValue();
    }
}
