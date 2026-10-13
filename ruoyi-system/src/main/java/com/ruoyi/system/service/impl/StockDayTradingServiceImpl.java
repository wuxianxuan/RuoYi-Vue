package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.service.IStockDayTradingService;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 趋势积累池减法实现
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法：逐个复查分组内股票，将趋势不再向上的股票移除。
 * 趋势判定复用 StockRisingTrendServiceImpl 的「逐步上涨」算法（isRisingTrend），
 * K线回看窗口与目标分组名均与趋势上涨选股任务保持一致（读取 stock.rising-trend.* 配置）。
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
    private IStockRisingTrendService stockRisingTrendService;

    /** 目标趋势积累分组名（与趋势上涨选股任务保持一致） */
    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String targetGroupName;

    /** K线回看日历天数（与趋势上涨选股任务的 lookback-days 保持一致） */
    @Value("${stock.rising-trend.lookback-days:30}")
    private int trendLookbackDays;

    @Override
    public void execute()
    {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(trendLookbackDays);
        log.info("========== 趋势积累池减法开始 ==========");
        log.info("参数：targetGroupName={} trendLookbackDays={} endDate={} startDate={}",
                targetGroupName, trendLookbackDays, endDate, startDate);

        // 1. 查找趋势积累分组
        StockGroup group = findGroupByName(targetGroupName);
        if (group == null)
        {
            log.warn("未找到目标分组 [{}]，跳过减法", targetGroupName);
            return;
        }

        // 2. 取分组内现有股票
        List<Stock> poolStocks = stockGroupService.selectStocksByGroupId(group.getId());
        if (poolStocks == null || poolStocks.isEmpty())
        {
            log.warn("分组 [{}] 内无股票，跳过减法", targetGroupName);
            return;
        }
        List<String> codes = poolStocks.stream()
                .map(Stock::getStockCode)
                .filter(code -> code != null)
                .distinct()
                .collect(Collectors.toList());
        log.info("分组 [{}] 当前 {} 只，开始逐个复查趋势", targetGroupName, codes.size());

        // 3. 批量拉取日K线
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate.toString(), endDate.toString());
        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream()
                        .filter(k -> k.getStockCode() != null)
                        .collect(Collectors.groupingBy(StockKline::getStockCode));

        // 4. 找出趋势不再向上的股票（做减法）
        List<String> removeCodes = new ArrayList<>();
        for (String code : codes)
        {
            List<StockKline> klines = klineMap.get(code);
            // 无K线数据或趋势不再向上 → 移除
            if (klines == null || klines.isEmpty() || !stockRisingTrendService.isRisingTrend(klines))
            {
                removeCodes.add(code);
            }
        }

        // 5. 移除
        if (removeCodes.isEmpty())
        {
            log.info("分组 [{}] 无需要移除的股票，任务结束", targetGroupName);
            return;
        }

        stockGroupService.deleteGroupStocks(group.getId(), removeCodes.toArray(new String[0]));
        log.info("从分组 [{}] 移除 {} 只（趋势不再向上），剩余 {} 只",
                targetGroupName, removeCodes.size(), codes.size() - removeCodes.size());
        for (String code : removeCodes)
        {
            log.info("    {}", code);
        }
        log.info("========== 趋势积累池减法完成 ==========");
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
}
