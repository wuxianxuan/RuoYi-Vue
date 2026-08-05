package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockIndicatorMapper;
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
 * 采用业界标准的多信号共振评分体系，检测底部反转信号：
 * 1. 底部K线形态（锤子线/吞没/启明星/刺透）
 * 2. MACD底背离（价格新低但DIF不创新低）
 * 3. RSI超卖恢复（RSI14曾<30后回升）
 * 4. 放量确认（量比≥1.5 + 突破20日均量）
 * 5. 前期下跌趋势确认（收盘<MA20 + 线性回归斜率<0）
 *
 * 满分100分，≥40分触发反转信号，≥60分为S级（强反转），40-59分为A级（中等反转）
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

    // ==================== K线查询参数 ====================
    /** 最少需要的日K线记录数 */
    private static final int MIN_KLINES = 25;
    /** 从数据库拉取的日历天数（覆盖约45个交易日，满足40日发散窗口） */
    private static final int CALENDAR_LOOKBACK = 65;

    // ==================== MACD参数 ====================
    private static final int EMA_FAST = 12;
    private static final int EMA_SLOW = 26;
    /** MACD底背离比较窗口（交易日数） */
    private static final int DIVERGENCE_WINDOW = 20;

    // ==================== 评分体系 ====================
    /** 触发反转信号的最低总分 */
    private static final int SCORE_THRESHOLD = 40;
    /** S级（强反转）阈值 */
    private static final int SCORE_STRONG = 60;

    // 各信号分值
    private static final int SCORE_CANDLESTICK = 15;
    private static final int SCORE_MACD_DIVERGENCE = 25;
    private static final int SCORE_RSI_OVERSOLD = 15;
    private static final int SCORE_VOLUME = 20;
    private static final int SCORE_VOL_BONUS = 10;
    private static final int SCORE_DOWNTREND = 15;

    // RSI参数
    private static final int RSI_PERIOD = 14;
    private static final BigDecimal RSI_OVERSOLD = BigDecimal.valueOf(30);

    // 量比阈值
    private static final BigDecimal VOL_RATIO_THRESHOLD = new BigDecimal("1.5");

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    /**
     * 检测结果内部类
     */
    private static class DetectionResult
    {
        int totalScore;
        String tier;            // "S", "A", or null
        List<String> signals;   // 触发的信号描述
        boolean isReversal;     // totalScore >= SCORE_THRESHOLD

        DetectionResult()
        {
            this.signals = new ArrayList<>();
        }
    }

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

            List<String> stockCodes = stocks.stream()
                    .map(Stock::getStockCode)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 2. 批量查询日K线数据
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

            // 3. 批量查询最新技术指标（用于MA20/量比/趋势斜率的优先读取）
            List<StockIndicator> allIndicators = stockIndicatorMapper.selectLatestByStockCodes(
                    stockCodes, "D");
            log.info("指标数据总量: {}", allIndicators != null ? allIndicators.size() : 0);

            Map<String, StockIndicator> indicatorMap = (allIndicators != null)
                    ? allIndicators.stream().collect(Collectors.toMap(
                        StockIndicator::getStockCode, i -> i, (a, b) -> a))
                    : Collections.emptyMap();

            // 4. 按股票代码分组K线
            Map<String, List<StockKline>> klineMap = allKlines.stream()
                    .collect(Collectors.groupingBy(StockKline::getStockCode));

            // 5. 逐股评分检测
            List<Long> reversalStockIds = new ArrayList<>();
            int sTierCount = 0, aTierCount = 0;

            for (Stock stock : stocks)
            {
                List<StockKline> klines = klineMap.get(stock.getStockCode());
                if (klines == null || klines.size() < minKlines)
                {
                    continue;
                }

                // 按交易时间升序排列
                klines.sort(Comparator.comparing(StockKline::getTradeTime));

                StockIndicator indicator = indicatorMap.get(stock.getStockCode());

                DetectionResult result = evaluateStock(klines, indicator);

                if (result.isReversal)
                {
                    reversalStockIds.add(stock.getId());
                    if ("S".equals(result.tier))
                    {
                        sTierCount++;
                    }
                    else
                    {
                        aTierCount++;
                    }

                    log.info("[{}] {} 触发反转信号 评分={} 等级={} 信号=[{}]",
                            stock.getStockCode(), stock.getStockName(),
                            result.totalScore, result.tier,
                            String.join(", ", result.signals));
                }
            }

            log.info("检测结果: S级={}, A级={}, 总计={}", sTierCount, aTierCount, reversalStockIds.size());

            // 6. 创建分组并关联股票
            if (reversalStockIds.isEmpty())
            {
                log.info("无符合条件的股票");
                return;
            }

            String groupName = "趋势反转_" + LocalDate.now().toString();
            StockGroup group = new StockGroup();
            group.setGroupName(groupName);
            group.setStockIds(reversalStockIds.toArray(new Long[0]));
            group.setCreateBy("system");
            group.setRemark(String.format("S级:%d A级:%d", sTierCount, aTierCount));

            stockGroupService.insertGroup(group);
            log.info("趋势反转检测完成: 分组=[{}], S级={}, A级={}, 股票数量={}",
                    groupName, sTierCount, aTierCount, reversalStockIds.size());
        }
        catch (Exception e)
        {
            log.error("趋势反转检测任务执行异常", e);
        }
    }

    // ==================== 主评分编排 ====================

    /**
     * 对单只股票进行多信号综合评分
     *
     * @param klines    K线列表，按 trade_time 升序排列（至少25条）
     * @param indicator 最新技术指标（可为null，此时从K线兜底计算）
     * @return 检测结果（总分、等级、触发信号）
     */
    private DetectionResult evaluateStock(List<StockKline> klines, StockIndicator indicator)
    {
        DetectionResult result = new DetectionResult();

        // ---- 信号1: 底部K线形态（0或15分） ----
        int candleScore = detectCandlestickPatterns(klines);
        if (candleScore > 0)
        {
            result.totalScore += candleScore;
            result.signals.add("底部蜡烛形态(+" + candleScore + ")");
        }

        // ---- 信号2: MACD底背离（0或25分） ----
        boolean macdDiv = detectMACDBottomDivergence(klines);
        if (macdDiv)
        {
            result.totalScore += SCORE_MACD_DIVERGENCE;
            result.signals.add("MACD底背离(+" + SCORE_MACD_DIVERGENCE + ")");
        }

        // ---- 信号3: RSI超卖恢复（0或15分） ----
        boolean rsiRecovery = detectRSIOversoldRecovery(klines);
        if (rsiRecovery)
        {
            result.totalScore += SCORE_RSI_OVERSOLD;
            result.signals.add("RSI超卖恢复(+" + SCORE_RSI_OVERSOLD + ")");
        }

        // ---- 信号4: 放量确认（0~30分） ----
        int volScore = evaluateVolumeConfirmation(klines, indicator);
        if (volScore > 0)
        {
            result.totalScore += volScore;
            result.signals.add("放量确认(+" + volScore + ")");
        }

        // ---- 信号5: 前期下跌趋势确认（0~15分） ----
        int downtrendScore = evaluatePriorDowntrend(klines, indicator);
        if (downtrendScore > 0)
        {
            result.totalScore += downtrendScore;
            result.signals.add("前期下跌趋势(+" + downtrendScore + ")");
        }

        // ---- 等级判定 ----
        if (result.totalScore >= SCORE_THRESHOLD)
        {
            result.isReversal = true;
            result.tier = result.totalScore >= SCORE_STRONG ? "S" : "A";
        }

        return result;
    }

    // ==================== 信号1: 底部K线形态检测 ====================

    /**
     * 检测最近几根K线是否存在底部反转形态
     *
     * 依次检查最新蜡烛的锤子线、吞没、启明星、刺透形态，
     * 任一命中即返回 SCORE_CANDLESTICK (15分)
     */
    private int detectCandlestickPatterns(List<StockKline> klines)
    {
        int n = klines.size();
        if (n < 3) return 0;

        StockKline latest = klines.get(n - 1);
        StockKline prev = klines.get(n - 2);
        StockKline prevPrev = klines.get(n - 3);

        // 1. 最新蜡烛为锤子线
        if (isHammer(latest)) return SCORE_CANDLESTICK;

        // 2. 最新两根组成看涨吞没
        if (isBullishEngulfing(prev, latest)) return SCORE_CANDLESTICK;

        // 3. 最新三根组成启明星
        if (isMorningStar(prevPrev, prev, latest)) return SCORE_CANDLESTICK;

        // 4. 最新两根组成刺透形态
        if (isPiercingPattern(prev, latest)) return SCORE_CANDLESTICK;

        // 5. 倒数第二根为锤子线（稍早一天的信号也有效）
        if (isHammer(prev)) return SCORE_CANDLESTICK;

        return 0;
    }

    /**
     * 锤子线（Hammer）
     *
     * 特征：实体较小且位于K线上端，下影线 ≥ 2倍实体，上影线极短（< 10%实体）
     * 含义：盘中空方一度打压但被多方强力拉回，是底部反转信号
     */
    private boolean isHammer(StockKline k)
    {
        BigDecimal open = k.getOpenPrice();
        BigDecimal close = k.getClosePrice();
        BigDecimal high = k.getHighPrice();
        BigDecimal low = k.getLowPrice();
        if (anyNull(open, close, high, low)) return false;

        BigDecimal body = close.subtract(open).abs();
        if (body.compareTo(BigDecimal.ZERO) == 0) return false; // 十字星不算锤子线

        BigDecimal upperShadow = high.subtract(open.max(close));
        BigDecimal lowerShadow = open.min(close).subtract(low);

        // 下影线 >= 2 * 实体
        if (lowerShadow.compareTo(body.multiply(BigDecimal.valueOf(2))) < 0) return false;

        // 上影线极短（< 10% 实体）
        if (upperShadow.compareTo(body.multiply(new BigDecimal("0.1"))) > 0) return false;

        // 实体位于K线上端：(收盘与开盘的最大值 - 最低价) > 0.66 * 总波幅
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.ZERO) == 0) return false;

        BigDecimal bodyUpper = open.max(close);
        return bodyUpper.subtract(low)
                .compareTo(range.multiply(new BigDecimal("0.66"))) > 0;
    }

    /**
     * 看涨吞没（Bullish Engulfing）
     *
     * 特征：前一根为阴线（收盘 < 开盘），当前为阳线（收盘 > 开盘），
     *       且当前实体完全吞没前一根实体（当前开盘 < 前收盘 且 当前收盘 > 前开盘）
     */
    private boolean isBullishEngulfing(StockKline prev, StockKline curr)
    {
        BigDecimal prevOpen = prev.getOpenPrice();
        BigDecimal prevClose = prev.getClosePrice();
        BigDecimal currOpen = curr.getOpenPrice();
        BigDecimal currClose = curr.getClosePrice();
        if (anyNull(prevOpen, prevClose, currOpen, currClose)) return false;

        // 前一根为阴线
        if (prevClose.compareTo(prevOpen) >= 0) return false;

        // 当前为阳线
        if (currClose.compareTo(currOpen) <= 0) return false;

        // 当前实体吞没前一根实体
        return currOpen.compareTo(prevClose) < 0 && currClose.compareTo(prevOpen) > 0;
    }

    /**
     * 启明星 / 早晨之星（Morning Star）
     *
     * 特征（三根K线）：
     *   - 第一根：大阴线
     *   - 第二根：小实体（实体 < 第一根实体的50%），可以是阴线或阳线
     *   - 第三根：阳线，收盘价回到第一根实体的中点以上
     */
    private boolean isMorningStar(StockKline first, StockKline second, StockKline third)
    {
        BigDecimal fOpen = first.getOpenPrice();
        BigDecimal fClose = first.getClosePrice();
        BigDecimal sOpen = second.getOpenPrice();
        BigDecimal sClose = second.getClosePrice();
        BigDecimal tOpen = third.getOpenPrice();
        BigDecimal tClose = third.getClosePrice();
        if (anyNull(fOpen, fClose, sOpen, sClose, tOpen, tClose)) return false;

        // 第一根为阴线
        if (fClose.compareTo(fOpen) >= 0) return false;

        BigDecimal firstBody = fOpen.subtract(fClose);
        if (firstBody.compareTo(BigDecimal.ZERO) <= 0) return false;

        // 第二根实体 < 第一根实体的50%（小实体/星线）
        BigDecimal secondBody = sClose.subtract(sOpen).abs();
        if (secondBody.compareTo(firstBody.multiply(new BigDecimal("0.5"))) > 0) return false;

        // 第三根为阳线
        if (tClose.compareTo(tOpen) <= 0) return false;

        // 第三根收盘 >= 第一根实体中点
        BigDecimal firstBodyMid = fOpen.add(fClose).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        return tClose.compareTo(firstBodyMid) >= 0;
    }

    /**
     * 刺透形态（Piercing Pattern）
     *
     * 特征：前一根为阴线，当前为阳线，
     *       当前开盘低于前最低价（跳空低开），当前收盘高于前实体中点（刺穿超过一半）
     */
    private boolean isPiercingPattern(StockKline prev, StockKline curr)
    {
        BigDecimal pOpen = prev.getOpenPrice();
        BigDecimal pClose = prev.getClosePrice();
        BigDecimal pLow = prev.getLowPrice();
        BigDecimal cOpen = curr.getOpenPrice();
        BigDecimal cClose = curr.getClosePrice();
        if (anyNull(pOpen, pClose, pLow, cOpen, cClose)) return false;

        // 前一根为阴线
        if (pClose.compareTo(pOpen) >= 0) return false;

        // 当前为阳线
        if (cClose.compareTo(cOpen) <= 0) return false;

        // 当前开盘 < 前最低价（跳空低开）
        if (cOpen.compareTo(pLow) >= 0) return false;

        // 当前收盘 > 前实体中点（刺穿超过一半）
        BigDecimal prevBodyMid = pOpen.add(pClose).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        return cClose.compareTo(prevBodyMid) > 0;
    }

    // ==================== 信号2: MACD底背离检测 ====================

    /**
     * 从收盘价序列计算 MACD DIF 值（EMA12 - EMA26）
     *
     * 首值作为 EMA 种子，EMA_SLOW-1 天之后DIF有效
     */
    private BigDecimal[] computeDIF(List<BigDecimal> closes)
    {
        int n = closes.size();
        BigDecimal[] ema12 = new BigDecimal[n];
        BigDecimal[] ema26 = new BigDecimal[n];
        BigDecimal[] dif = new BigDecimal[n];

        BigDecimal mul12 = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(EMA_FAST + 1), 8, RoundingMode.HALF_UP);
        BigDecimal mul26 = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(EMA_SLOW + 1), 8, RoundingMode.HALF_UP);

        for (int i = 0; i < n; i++)
        {
            BigDecimal c = closes.get(i);
            if (c == null) continue;

            if (i == 0)
            {
                ema12[i] = c;
                ema26[i] = c;
            }
            else
            {
                if (ema12[i - 1] != null)
                {
                    ema12[i] = c.multiply(mul12)
                            .add(ema12[i - 1].multiply(BigDecimal.ONE.subtract(mul12)));
                }
                if (ema26[i - 1] != null)
                {
                    ema26[i] = c.multiply(mul26)
                            .add(ema26[i - 1].multiply(BigDecimal.ONE.subtract(mul26)));
                }
            }

            if (i >= EMA_SLOW - 1 && ema12[i] != null && ema26[i] != null)
            {
                dif[i] = ema12[i].subtract(ema26[i]);
            }
        }
        return dif;
    }

    /**
     * MACD底背离检测（20日极值比较法）
     *
     * 将最近40个交易日分为两段各20天：
     *   旧区间 [n-40, n-21]：找到最低价极其对应DIF
     *   新区间 [n-20, n-1]：找到最低价极其对应DIF
     *
     * 底背离条件：新区间最低价更低（价格创新低）但 DIF 更高（动能未跟随）
     */
    private boolean detectMACDBottomDivergence(List<StockKline> klines)
    {
        int n = klines.size();
        if (n < 2 * DIVERGENCE_WINDOW) return false;

        // 提取收盘价并计算 DIF
        List<BigDecimal> closes = klines.stream()
                .map(StockKline::getClosePrice)
                .collect(Collectors.toList());
        BigDecimal[] dif = computeDIF(closes);

        // ---- 旧区间 [n-40, n-21]：找最低价及对应DIF ----
        int oldStart = n - 2 * DIVERGENCE_WINDOW;
        int oldEnd = n - DIVERGENCE_WINDOW - 1;
        BigDecimal oldLowestPrice = null;
        BigDecimal oldLowestDif = null;

        for (int i = oldStart; i <= oldEnd; i++)
        {
            BigDecimal low = klines.get(i).getLowPrice();
            if (low == null) continue;
            if (oldLowestPrice == null || low.compareTo(oldLowestPrice) < 0)
            {
                oldLowestPrice = low;
                oldLowestDif = dif[i];
            }
        }

        // ---- 新区间 [n-20, n-1]：找最低价及对应DIF ----
        int newStart = n - DIVERGENCE_WINDOW;
        int newEnd = n - 1;
        BigDecimal newLowestPrice = null;
        BigDecimal newLowestDif = null;

        for (int i = newStart; i <= newEnd; i++)
        {
            BigDecimal low = klines.get(i).getLowPrice();
            if (low == null) continue;
            if (newLowestPrice == null || low.compareTo(newLowestPrice) < 0)
            {
                newLowestPrice = low;
                newLowestDif = dif[i];
            }
        }

        if (oldLowestPrice == null || newLowestPrice == null) return false;
        if (oldLowestDif == null || newLowestDif == null) return false;

        // 底背离：价格创新低 但 DIF 未创新低（DIF更高 = 下跌动能衰竭）
        return newLowestPrice.compareTo(oldLowestPrice) < 0
                && newLowestDif.compareTo(oldLowestDif) > 0;
    }

    // ==================== 信号3: RSI超卖恢复检测 ====================

    /**
     * 计算 RSI14（Wilder's smoothed 方法）
     *
     * 与 StockIndicatorServiceImpl.calcRSI 使用相同的算法
     */
    private BigDecimal[] computeRSI14(List<BigDecimal> closes)
    {
        int n = closes.size();
        BigDecimal[] result = new BigDecimal[n];
        if (n <= RSI_PERIOD) return result;

        // 首期：简单平均
        BigDecimal sumGain = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        for (int i = 1; i <= RSI_PERIOD; i++)
        {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            if (change.compareTo(BigDecimal.ZERO) > 0)
            {
                sumGain = sumGain.add(change);
            }
            else
            {
                sumLoss = sumLoss.add(change.abs());
            }
        }

        BigDecimal avgGain = sumGain.divide(BigDecimal.valueOf(RSI_PERIOD), 8, RoundingMode.HALF_UP);
        BigDecimal avgLoss = sumLoss.divide(BigDecimal.valueOf(RSI_PERIOD), 8, RoundingMode.HALF_UP);

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0)
        {
            result[RSI_PERIOD] = BigDecimal.valueOf(100);
        }
        else
        {
            BigDecimal rs = avgGain.divide(avgLoss, 8, RoundingMode.HALF_UP);
            result[RSI_PERIOD] = BigDecimal.valueOf(100).subtract(
                    BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_UP));
        }

        // 后续各期平滑
        BigDecimal prevGain = avgGain;
        BigDecimal prevLoss = avgLoss;
        for (int i = RSI_PERIOD + 1; i < n; i++)
        {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

            prevGain = prevGain.multiply(BigDecimal.valueOf(RSI_PERIOD - 1)).add(gain)
                    .divide(BigDecimal.valueOf(RSI_PERIOD), 8, RoundingMode.HALF_UP);
            prevLoss = prevLoss.multiply(BigDecimal.valueOf(RSI_PERIOD - 1)).add(loss)
                    .divide(BigDecimal.valueOf(RSI_PERIOD), 8, RoundingMode.HALF_UP);

            if (prevLoss.compareTo(BigDecimal.ZERO) == 0)
            {
                result[i] = BigDecimal.valueOf(100);
            }
            else
            {
                BigDecimal rs = prevGain.divide(prevLoss, 8, RoundingMode.HALF_UP);
                result[i] = BigDecimal.valueOf(100).subtract(
                        BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 8, RoundingMode.HALF_UP));
            }
        }
        return result;
    }

    /**
     * RSI超卖恢复检测
     *
     * 条件：
     *   1. 最近5个交易日中至少有一天 RSI14 < 30（曾处于超卖区）
     *   2. 当前 RSI14 > 5天前 RSI14（正在回升）
     */
    private boolean detectRSIOversoldRecovery(List<StockKline> klines)
    {
        int n = klines.size();
        if (n < RSI_PERIOD + 6) return false; // 至少需要 RSI14 可计算 + 5天回看

        List<BigDecimal> closes = klines.stream()
                .map(StockKline::getClosePrice)
                .collect(Collectors.toList());
        BigDecimal[] rsi14 = computeRSI14(closes);

        BigDecimal currentRSI = rsi14[n - 1];
        if (currentRSI == null) return false;

        // 检查最近5天是否有超卖（RSI < 30）
        int lookback = Math.min(5, n - RSI_PERIOD - 1);
        boolean wasOversold = false;
        for (int i = n - 1 - lookback; i < n; i++)
        {
            if (rsi14[i] != null && rsi14[i].compareTo(RSI_OVERSOLD) < 0)
            {
                wasOversold = true;
                break;
            }
        }

        if (!wasOversold) return false;

        // 当前RSI > 5天前RSI（回升）
        BigDecimal rsi5DaysAgo = rsi14[n - 1 - lookback];
        if (rsi5DaysAgo == null) return false;

        return currentRSI.compareTo(rsi5DaysAgo) > 0;
    }

    // ==================== 信号4: 放量确认 ====================

    /**
     * 放量确认评分
     *
     * 优先使用 StockIndicator.volRatio（量比 = 当日量 / 5日均量），
     * 没有则从K线兜底计算。
     *
     * 得分规则：
     *   - 量比 ≥ 1.5 → +20分
     *   - 当日量 > 20日均量 → +10分
     *   最高30分
     */
    private int evaluateVolumeConfirmation(List<StockKline> klines, StockIndicator indicator)
    {
        int n = klines.size();
        if (n < 20) return 0;

        int score = 0;
        StockKline latest = klines.get(n - 1);
        Long latestVol = latest.getVolume();

        // ---- 量比检查 ----
        BigDecimal volRatio = null;
        if (indicator != null && indicator.getVolRatio() != null)
        {
            volRatio = indicator.getVolRatio();
        }
        else if (latestVol != null && n >= 5)
        {
            // 兜底：从K线计算 5日均量比
            long vol5Sum = 0;
            for (int i = n - 5; i < n; i++)
            {
                Long v = klines.get(i).getVolume();
                if (v != null) vol5Sum += v;
            }
            if (vol5Sum > 0)
            {
                volRatio = BigDecimal.valueOf(latestVol * 5)
                        .divide(BigDecimal.valueOf(vol5Sum), 8, RoundingMode.HALF_UP);
            }
        }

        if (volRatio != null && volRatio.compareTo(VOL_RATIO_THRESHOLD) >= 0)
        {
            score += SCORE_VOLUME; // +20
        }

        // ---- 20日均量突破检查 ----
        if (latestVol != null)
        {
            long vol20Sum = 0;
            int count = 0;
            for (int i = Math.max(0, n - 20); i < n; i++)
            {
                Long v = klines.get(i).getVolume();
                if (v != null) { vol20Sum += v; count++; }
            }
            if (count > 0)
            {
                BigDecimal avg20 = BigDecimal.valueOf(vol20Sum)
                        .divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
                if (BigDecimal.valueOf(latestVol).compareTo(avg20) > 0)
                {
                    score += SCORE_VOL_BONUS; // +10
                }
            }
        }

        return score;
    }

    // ==================== 信号5: 前期下跌趋势确认 ====================

    /**
     * 前期下跌趋势确认评分
     *
     * 条件A：最新收盘价 < MA20（价格在均线下方，确认前期弱势） → +7分
     * 条件B：前期线性回归斜率 < 0（排除最近2天上涨，只看之前趋势） → +8分
     *
     * 两个条件同时满足才给满分15分
     */
    private int evaluatePriorDowntrend(List<StockKline> klines, StockIndicator indicator)
    {
        int n = klines.size();
        if (n < 22) return 0;

        int score = 0;
        StockKline latest = klines.get(n - 1);
        BigDecimal latestClose = latest.getClosePrice();
        if (latestClose == null) return 0;

        // ---- 条件A: 收盘价 < MA20 ----
        boolean belowMA20 = false;
        if (indicator != null && indicator.getMa20() != null)
        {
            belowMA20 = latestClose.compareTo(indicator.getMa20()) < 0;
        }
        else
        {
            // 兜底：从K线计算MA20
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (int i = Math.max(0, n - 20); i < n; i++)
            {
                BigDecimal c = klines.get(i).getClosePrice();
                if (c != null) { sum = sum.add(c); count++; }
            }
            if (count > 0)
            {
                BigDecimal ma20 = sum.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_UP);
                belowMA20 = latestClose.compareTo(ma20) < 0;
            }
        }
        if (belowMA20) score += 7;

        // ---- 条件B: 前期线性回归斜率 < 0（排除最近2天） ----
        boolean downtrendSlope = false;
        if (indicator != null && indicator.getTrendSlope() != null)
        {
            downtrendSlope = indicator.getTrendSlope().compareTo(BigDecimal.ZERO) < 0;
        }
        else
        {
            // 兜底：从K线计算
            int priorEnd = n - 2;
            if (priorEnd >= 5)
            {
                BigDecimal sumX = BigDecimal.ZERO, sumY = BigDecimal.ZERO;
                BigDecimal sumXY = BigDecimal.ZERO, sumX2 = BigDecimal.ZERO;
                boolean valid = true;
                for (int i = 0; i < priorEnd; i++)
                {
                    BigDecimal close = klines.get(i).getClosePrice();
                    if (close == null) { valid = false; break; }
                    BigDecimal xi = BigDecimal.valueOf(i);
                    sumX = sumX.add(xi);
                    sumY = sumY.add(close);
                    sumXY = sumXY.add(xi.multiply(close));
                    sumX2 = sumX2.add(xi.multiply(xi));
                }
                if (valid)
                {
                    BigDecimal numerator = BigDecimal.valueOf(priorEnd).multiply(sumXY)
                            .subtract(sumX.multiply(sumY));
                    BigDecimal denominator = BigDecimal.valueOf(priorEnd).multiply(sumX2)
                            .subtract(sumX.multiply(sumX));
                    if (denominator.compareTo(BigDecimal.ZERO) != 0)
                    {
                        BigDecimal slope = numerator.divide(denominator, 8, RoundingMode.HALF_UP);
                        downtrendSlope = slope.compareTo(BigDecimal.ZERO) < 0;
                    }
                }
            }
        }
        if (downtrendSlope) score += 8;

        // 两个条件同时满足才给满分
        return (score >= SCORE_DOWNTREND) ? SCORE_DOWNTREND : score;
    }

    // ==================== 工具方法 ====================

    /**
     * 检查是否有 BigDecimal 为 null
     */
    private boolean anyNull(BigDecimal... values)
    {
        for (BigDecimal v : values)
        {
            if (v == null) return true;
        }
        return false;
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
