package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockRecommend;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 股票推荐引擎实现
 *
 * 策略1：均线多头排列 + 板块共振（最高80分）
 * 策略2：MACD零轴金叉 + 放量（最高80分）
 * 融合：取最优策略得分
 */
@Service
public class StockRecommendServiceImpl implements IStockRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockRecommendMapper stockRecommendMapper;

    /** 最低K线数量要求 */
    private static final int MIN_KLINES = 20;

    /** 最高MA得分 */
    private static final BigDecimal MAX_MA_SCORE = BigDecimal.valueOf(50);

    /** 最高MACD得分 */
    private static final BigDecimal MAX_MACD_SCORE = BigDecimal.valueOf(60);

    /** 放量加分 */
    private static final BigDecimal VOL_BONUS = BigDecimal.valueOf(20);

    /** 板块涨停股单只加分 */
    private static final BigDecimal PLATE_STOCK_BONUS = BigDecimal.valueOf(10);

    /** 板块加分上限 */
    private static final BigDecimal PLATE_BONUS_MAX = BigDecimal.valueOf(30);

    /** 量比阈值 */
    private static final BigDecimal VOL_RATIO_THRESHOLD = new BigDecimal("1.5");

    @Override
    public int getPlateHeat(String plateName, String tradeDate)
    {
        if (plateName == null || plateName.isEmpty() || tradeDate == null || tradeDate.isEmpty())
        {
            return 0;
        }
        try
        {
            // 直接查询 stock_limit_up_record 中该板块的涨停股数
            List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records =
                    stockLimitUpRecordMapper.collectStockData(null);
            if (records == null) return 0;

            int count = 0;
            for (com.ruoyi.system.domain.stock.StockLimitUpRecord r : records)
            {
                if (plateName.equals(r.getPlateSecuName())
                        && r.getPlateStockUpNum() != null
                        && r.getPlateStockUpNum() > 0)
                {
                    count = Math.max(count, r.getPlateStockUpNum());
                }
            }
            return count;
        }
        catch (Exception e)
        {
            log.debug("查询板块热度失败: plateName={}, error={}", plateName, e.getMessage());
            return 0;
        }
    }

    @Override
    public BigDecimal getStockPlateHeat(String stockCode, String tradeDate)
    {
        // 使用前一天的数据（如果是当天盘后则用当天）
        try
        {
            List<com.ruoyi.system.domain.stock.StockLimitUpRecord> records =
                    stockLimitUpRecordMapper.collectStockData(null);
            if (records == null || records.isEmpty()) return BigDecimal.ZERO;

            // 找到该股票所属板块的热度
            Set<String> seenPlates = new HashSet<>();
            int totalHeat = 0;
            int plateCount = 0;

            for (com.ruoyi.system.domain.stock.StockLimitUpRecord r : records)
            {
                if (tradeDate.equals(r.getTradeDate()) && r.getPlateStockUpNum() != null)
                {
                    String plateName = r.getPlateSecuName();
                    if (plateName != null && seenPlates.add(plateName))
                    {
                        totalHeat += r.getPlateStockUpNum();
                        plateCount++;
                    }
                }
            }

            if (plateCount == 0) return BigDecimal.ZERO;
            return BigDecimal.valueOf(totalHeat)
                    .divide(BigDecimal.valueOf(plateCount), 4, RoundingMode.HALF_UP);
        }
        catch (Exception e)
        {
            log.debug("查询股票板块热度失败: stockCode={}, error={}", stockCode, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    @Override
    public int executeRecommend(String tradeDate, int topN)
    {
        log.info("========== 推荐引擎开始: tradeDate={}, topN={} ==========", tradeDate, topN);

        try
        {
            // 1. 获取全量 SH/SZ 股票
            List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ"));
            if (stocks == null || stocks.isEmpty())
            {
                log.warn("未找到股票记录");
                return 0;
            }
            log.info("候选股票池: {} 只", stocks.size());

            // 2. 计算前一天（最近的交易日）日期
            String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            // 3. 逐股评分
            List<StockRecommend> candidates = new ArrayList<>();

            for (Stock stock : stocks)
            {
                try
                {
                    String stockCode = stock.getStockCode();
                    if (stockCode == null || stockCode.isEmpty()) continue;

                    // 获取最新指标
                    StockIndicator indicator = stockIndicatorMapper.selectLatestByStockCode(stockCode, "D");
                    if (indicator == null)
                    {
                        continue;
                    }

                    // 获取板块热度
                    BigDecimal plateHeat = getStockPlateHeat(stockCode, yesterday);

                    // 策略1: 均线多头排列 + 板块共振
                    BigDecimal score1 = scoreStrategy1(indicator, plateHeat);

                    // 策略2: MACD零轴金叉 + 放量
                    BigDecimal score2 = scoreStrategy2(indicator);

                    // 融合：取最优策略得分
                    BigDecimal totalScore = score1.max(score2);

                    if (totalScore.compareTo(BigDecimal.ZERO) > 0)
                    {
                        String reason = buildReason(score1, score2, indicator, plateHeat);
                        StockRecommend rec = new StockRecommend();
                        rec.setRecommendDate(java.sql.Date.valueOf(LocalDate.now()));
                        rec.setStockId(stock.getId());
                        rec.setStockCode(stock.getStockCode());
                        rec.setStockName(stock.getStockName());
                        rec.setTotalScore(totalScore);
                        rec.setTrendScore(score1.max(score2));
                        rec.setPlateScore(plateHeat);
                        rec.setFundScore(BigDecimal.ZERO);
                        rec.setBaseScore(BigDecimal.ZERO);
                        rec.setReason(reason);
                        rec.setStatus("0");
                        rec.setCreateBy("system");
                        rec.setUpdateBy("system");
                        candidates.add(rec);
                    }
                }
                catch (Exception e)
                {
                    log.debug("评分异常: stockCode={}, error={}", stock.getStockCode(), e.getMessage());
                }
            }

            log.info("评分完成: {} 只股票有得分", candidates.size());

            // 4. 按总分降序排列
            candidates.sort(Comparator.comparing(StockRecommend::getTotalScore).reversed());

            // 5. 取 Top N
            List<StockRecommend> topList = candidates.subList(0, Math.min(topN, candidates.size()));

            // 6. 幂等：删除当天旧数据 → 插入新数据
            String todayStr = LocalDate.now().toString();
            stockRecommendMapper.deleteByDate(todayStr);

            if (!topList.isEmpty())
            {
                stockRecommendMapper.batchInsertRecommend(topList);
                log.info("推荐结果入库: {} 条", topList.size());
            }

            log.info("========== 推荐引擎完成: 生成 {} 条推荐 ==========", topList.size());
            return topList.size();
        }
        catch (Exception e)
        {
            log.error("推荐引擎执行异常", e);
            return 0;
        }
    }

    // ==================== 评分策略 ====================

    /**
     * 策略1：均线多头排列 + 板块共振
     *
     * 条件: MA5 > MA10 > MA20 > MA60（全部向上发散）
     * 得分: 多头成立50分 + 板块热度加成（每只涨停+10，上限30）
     */
    private BigDecimal scoreStrategy1(StockIndicator indicator, BigDecimal plateHeat)
    {
        if (indicator.getMa5() == null || indicator.getMa10() == null
                || indicator.getMa20() == null || indicator.getMa60() == null)
        {
            return BigDecimal.ZERO;
        }

        boolean isBull = indicator.getMa5().compareTo(indicator.getMa10()) > 0
                && indicator.getMa10().compareTo(indicator.getMa20()) > 0
                && indicator.getMa20().compareTo(indicator.getMa60()) > 0;

        if (!isBull)
        {
            return BigDecimal.ZERO;
        }

        BigDecimal score = MAX_MA_SCORE;  // 50分
        BigDecimal plateBonus = plateHeat.multiply(PLATE_STOCK_BONUS);
        if (plateBonus.compareTo(PLATE_BONUS_MAX) > 0)
        {
            plateBonus = PLATE_BONUS_MAX;
        }
        return score.add(plateBonus);
    }

    /**
     * 策略2：MACD零轴金叉 + 放量
     *
     * 条件: DIF > DEA 且 DIF > 0 且 DEA > 0（零轴上方金叉状态）
     * 量能: 量比 > 1.5
     * 得分: 金叉成立60分 + 放量20分
     */
    private BigDecimal scoreStrategy2(StockIndicator indicator)
    {
        if (indicator.getMacdDif() == null || indicator.getMacdDea() == null
                || indicator.getMacdBar() == null)
        {
            return BigDecimal.ZERO;
        }

        boolean goldenCross = indicator.getMacdDif().compareTo(BigDecimal.ZERO) > 0
                && indicator.getMacdDea().compareTo(BigDecimal.ZERO) > 0
                && indicator.getMacdDif().compareTo(indicator.getMacdDea()) > 0;

        if (!goldenCross)
        {
            return BigDecimal.ZERO;
        }

        BigDecimal score = MAX_MACD_SCORE;  // 60分

        // 放量加成
        if (indicator.getVolRatio() != null
                && indicator.getVolRatio().compareTo(VOL_RATIO_THRESHOLD) > 0)
        {
            score = score.add(VOL_BONUS);  // +20分
        }

        return score;
    }

    /**
     * 构造推荐理由
     */
    private String buildReason(BigDecimal score1, BigDecimal score2,
                               StockIndicator indicator, BigDecimal plateHeat)
    {
        List<String> reasons = new ArrayList<>();

        if (score1.compareTo(BigDecimal.ZERO) > 0)
        {
            reasons.add("均线多头排列");
            if (plateHeat.compareTo(BigDecimal.ZERO) > 0)
            {
                reasons.add("板块热度" + plateHeat);
            }
        }

        if (score2.compareTo(BigDecimal.ZERO) > 0)
        {
            reasons.add("MACD零轴金叉");
            if (indicator.getVolRatio() != null
                    && indicator.getVolRatio().compareTo(VOL_RATIO_THRESHOLD) > 0)
            {
                reasons.add("放量");
            }
        }

        return String.join(",", reasons);
    }
}
