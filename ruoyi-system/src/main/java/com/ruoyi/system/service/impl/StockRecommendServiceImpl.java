package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockIndicatorMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockLimitUpStatService;
import com.ruoyi.system.service.IStockPlateService;
import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票推荐引擎实现
 *
 * 基于「重点关注」板块（focus_flag=1），对每个板块下属股票按
 * 「强势趋势中继」多因子打分，取 Top N 只值得入场的股票生成分组。
 */
@Service
public class StockRecommendServiceImpl implements IStockRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendServiceImpl.class);

    @Autowired
    private IStockPlateService stockPlateService;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private IStockLimitUpStatService stockLimitUpStatService;

    /** 每个重点板块取几只股票 */
    @Value("${stock.recommend.top-stocks:10}")
    private int topStocks;

    /** 生成分组名前缀 */
    @Value("${stock.recommend.group-prefix:入场}")
    private String groupPrefix;

    /** 换手率阈值：低于此值视为冷门（资金热度 0 分） */
    @Value("${stock.recommend.turnover-cold:1}")
    private BigDecimal turnoverCold;

    /** 换手率健康区间下限（%） */
    @Value("${stock.recommend.turnover-healthy-low:2}")
    private BigDecimal turnoverHealthyLow;

    /** 换手率健康区间上限（%） */
    @Value("${stock.recommend.turnover-healthy-high:10}")
    private BigDecimal turnoverHealthyHigh;

    /** 换手率过热阈值（%），高于此值视为异常过热 */
    @Value("${stock.recommend.turnover-hot-high:20}")
    private BigDecimal turnoverHotHigh;

    /** 成交额下限过滤（单位与 stock.turnover_amount 一致），0 表示不过滤 */
    @Value("${stock.recommend.min-turnover-amount:0}")
    private BigDecimal minTurnoverAmount;

    /** 涨停活跃度权重（满分），按「涨停次数 / 板块内最高涨停次数」占比给分 */
    @Value("${stock.recommend.limitup-weight:30}")
    private int limitUpWeight;

    @Override
    public void executeFocusPlateRecommend()
    {
        log.info("========== 重点关注板块入场推荐引擎开始 ==========");

        // 1. 查询重点关注板块（focus_flag = 1）
        StockPlate filter = new StockPlate();
        filter.setFocusFlag(1);
        List<StockPlate> plates = stockPlateService.selectPlateList(filter);
        if (plates == null || plates.isEmpty())
        {
            log.warn("无板块配置重点关注（focus_flag=1），跳过推荐");
            return;
        }
        log.info("重点关注板块/概念共 {} 个", plates.size());

        String dateStr = LocalDate.now().toString();
        int groupCount = 0;

        for (StockPlate plate : plates)
        {
            try
            {
                if (recommendForPlate(plate, dateStr) > 0)
                {
                    groupCount++;
                }
            }
            catch (Exception e)
            {
                log.error("板块 [{}] 入场推荐异常", plate.getPlateName(), e);
            }
        }

        log.info("========== 重点关注板块入场推荐完成: 生成分组 {} 个 ==========", groupCount);
    }

    /**
     * 对单个重点板块计算值得入场的 Top N 股票并生成分组
     *
     * @return 生成分组中的股票数（0 表示未生成分组）
     */
    private int recommendForPlate(StockPlate plate, String dateStr)
    {
        // 2. 板块成分股
        List<Stock> stocks = stockPlateService.selectPlateStocks(plate.getId());
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("板块 [{}] 无成分股，跳过", plate.getPlateName());
            return 0;
        }

        // 3. 批量获取最新技术指标
        List<String> codes = stocks.stream()
                .map(Stock::getStockCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        Map<String, StockIndicator> indicatorMap = new HashMap<>();
        if (!codes.isEmpty())
        {
            List<StockIndicator> indicators = stockIndicatorMapper.selectLatestByStockCodes(codes, "D");
            if (indicators != null)
            {
                for (StockIndicator ind : indicators)
                {
                    indicatorMap.put(ind.getStockCode(), ind);
                }
            }
        }

        // 批量获取近1年（不含最近7天）涨停次数，作为打分权重指标
        Map<String, Integer> limitUpCountMap = stockLimitUpStatService.getLimitUpCountMap(codes);

        // 取板块内最高涨停次数作为分母，涨停次数按占比折算权重分
        int maxLimitUpCount = 0;
        for (Integer c : limitUpCountMap.values())
        {
            if (c != null && c > maxLimitUpCount)
            {
                maxLimitUpCount = c;
            }
        }

        // 4. 逐股打分（指标缺失或流动性不足的股票跳过）
        List<ScoredStock> scored = new ArrayList<>();
        int liquidityFiltered = 0;
        for (Stock s : stocks)
        {
            StockIndicator ind = indicatorMap.get(s.getStockCode());
            if (ind == null)
            {
                continue;
            }
            // 流动性过滤：成交额低于下限不推荐（0 表示不过滤）
            if (minTurnoverAmount != null && minTurnoverAmount.compareTo(BigDecimal.ZERO) > 0
                    && (s.getTurnoverAmount() == null || s.getTurnoverAmount().compareTo(minTurnoverAmount) < 0))
            {
                liquidityFiltered++;
                continue;
            }
            scored.add(new ScoredStock(s.getId(), s.getStockCode(), s.getStockName(),
                    scoreEntry(s, ind, limitUpCountMap, maxLimitUpCount)));
        }

        if (liquidityFiltered > 0)
        {
            log.info("板块 [{}] 成交额低于下限被过滤 {} 只", plate.getPlateName(), liquidityFiltered);
        }

        if (scored.isEmpty())
        {
            log.warn("板块 [{}] 无可用指标数据，跳过", plate.getPlateName());
            return 0;
        }

        // 5. 按得分降序取 Top N
        scored.sort(Comparator.comparingInt(ScoredStock::getScore).reversed());
        int topN = Math.min(topStocks, scored.size());
        List<ScoredStock> top = scored.subList(0, topN);

        // 6. 生成分组
        String groupName = groupPrefix + "_" + dateStr + "_" + plate.getPlateName();
        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockIds(top.stream().map(ScoredStock::getId).toArray(Long[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("Top%d 最高分%d", topN, top.get(0).getScore()));

        stockGroupService.insertGroup(group);
        log.info("创建入场分组: [{}], 股票数={}, 最高分={}", groupName, topN, top.get(0).getScore());
        for (ScoredStock ss : top)
        {
            log.info("  {} {} 评分={}", ss.getStockCode(), ss.getStockName(), ss.getScore());
        }
        return topN;
    }

    /**
     * 强势趋势中继打分（满分 130）
     * 因子：趋势多头排列(20) + MACD多头(15) + RSI健康(15) + 资金热度·换手率(20)
     *       + 量能·量比(15) + 趋势斜率向上(15) + 涨停活跃度(30)
     */
    private int scoreEntry(Stock stock, StockIndicator ind, Map<String, Integer> limitUpCountMap,
                           int maxLimitUpCount)
    {
        int score = 0;

        // 1. 趋势多头排列（20）
        BigDecimal ma5 = ind.getMa5();
        BigDecimal ma10 = ind.getMa10();
        BigDecimal ma20 = ind.getMa20();
        if (ma5 != null && ma10 != null && ma20 != null)
        {
            if (ma5.compareTo(ma10) > 0 && ma10.compareTo(ma20) > 0)
            {
                score += 20;
            }
            else if (ma5.compareTo(ma20) > 0)
            {
                score += 12;
            }
        }

        // 2. MACD 多头（15）
        if (ind.getMacdDif() != null && ind.getMacdDea() != null
                && ind.getMacdDif().compareTo(ind.getMacdDea()) > 0)
        {
            score += 15;
        }

        // 3. RSI 健康动能（15）
        BigDecimal rsi = ind.getRsi14();
        if (rsi != null)
        {
            BigDecimal fifty = new BigDecimal("50");
            BigDecimal seventyFive = new BigDecimal("75");
            BigDecimal fortyFive = new BigDecimal("45");
            BigDecimal eighty = new BigDecimal("80");
            if (rsi.compareTo(fifty) >= 0 && rsi.compareTo(seventyFive) <= 0)
            {
                score += 15;
            }
            else if ((rsi.compareTo(fortyFive) >= 0 && rsi.compareTo(fifty) < 0)
                    || (rsi.compareTo(seventyFive) > 0 && rsi.compareTo(eighty) <= 0))
            {
                score += 8;
            }
        }

        // 4. 资金热度·换手率（20）：换手率过低说明无资金参与，趋势再好也难有后续
        BigDecimal turnoverRate = stock.getTurnoverRate();
        if (turnoverRate != null)
        {
            if (turnoverRate.compareTo(turnoverHealthyLow) >= 0 && turnoverRate.compareTo(turnoverHealthyHigh) <= 0)
            {
                score += 20; // 健康活跃
            }
            else if ((turnoverRate.compareTo(turnoverCold) >= 0 && turnoverRate.compareTo(turnoverHealthyLow) < 0)
                    || (turnoverRate.compareTo(turnoverHealthyHigh) > 0 && turnoverRate.compareTo(turnoverHotHigh) <= 0))
            {
                score += 12; // 一般/偏热
            }
            else if (turnoverRate.compareTo(turnoverHotHigh) > 0)
            {
                score += 8; // 异常过热
            }
            // 低于 turnoverCold：冷门，得 0 分
        }

        // 5. 量能·量比（15）
        BigDecimal volRatio = ind.getVolRatio();
        if (volRatio != null)
        {
            BigDecimal one = BigDecimal.ONE;
            BigDecimal three = new BigDecimal("3");
            BigDecimal pointEight = new BigDecimal("0.8");
            if (volRatio.compareTo(one) >= 0 && volRatio.compareTo(three) <= 0)
            {
                score += 15;
            }
            else if (volRatio.compareTo(three) > 0
                    || (volRatio.compareTo(pointEight) >= 0 && volRatio.compareTo(one) < 0))
            {
                score += 8;
            }
        }

        // 6. 趋势斜率向上（15）
        if (ind.getTrendSlope() != null && ind.getTrendSlope().compareTo(BigDecimal.ZERO) > 0)
        {
            score += 15;
        }

        // 7. 涨停活跃度（30）：以板块内最高涨停次数为分母，按占比折算权重分
        //    涨停次数 / 最高涨停次数 * 权重，避免固定阈值过于武断
        if (maxLimitUpCount > 0)
        {
            Integer limitUpCount = limitUpCountMap.get(stock.getStockCode());
            if (limitUpCount != null && limitUpCount > 0)
            {
                score += (int) Math.round((double) limitUpCount * limitUpWeight / maxLimitUpCount);
            }
        }

        return score;
    }

    /** 打分结果内部类 */
    private static class ScoredStock
    {
        private final Long id;
        private final String stockCode;
        private final String stockName;
        private final int score;

        ScoredStock(Long id, String stockCode, String stockName, int score)
        {
            this.id = id;
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.score = score;
        }

        Long getId() { return id; }
        String getStockCode() { return stockCode; }
        String getStockName() { return stockName; }
        int getScore() { return score; }
    }
}
