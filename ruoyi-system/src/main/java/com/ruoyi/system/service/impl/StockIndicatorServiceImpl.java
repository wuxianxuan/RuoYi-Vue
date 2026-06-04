package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockIndicatorMapper;
import com.ruoyi.system.service.IStockIndicatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 技术指标计算服务
 *
 * 指标列表：MA5/10/20/60, MACD(12/26/9), RSI6/14, 量比, 趋势斜率
 * 所有计算使用 BigDecimal + HALF_UP，scale=8
 */
@Service
public class StockIndicatorServiceImpl implements IStockIndicatorService
{
    private static final Logger log = LoggerFactory.getLogger(StockIndicatorServiceImpl.class);

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    private static final int SCALE = 8;
    private static final RoundingMode RM = RoundingMode.HALF_UP;

    // MACD 参数
    private static final int EMA_FAST = 12;
    private static final int EMA_SLOW = 26;
    private static final int EMA_SIGNAL = 9;

    // RSI 参数
    private static final int RSI_PERIOD_6 = 6;
    private static final int RSI_PERIOD_14 = 14;

    // 量比参数
    private static final int VOL_MA_PERIOD = 5;

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Override
    public List<StockIndicator> calcIndicators(List<StockKline> klines)
    {
        if (klines == null || klines.isEmpty())
        {
            return new ArrayList<>();
        }

        int n = klines.size();
        List<StockIndicator> results = new ArrayList<>(n);

        // 提取 close 价格和成交量列表
        List<BigDecimal> closes = new ArrayList<>(n);
        List<Long> volumes = new ArrayList<>(n);
        for (StockKline k : klines)
        {
            closes.add(k.getClosePrice());
            volumes.add(k.getVolume() != null ? k.getVolume() : 0L);
        }

        // ==================== 计算 SMA ====================
        BigDecimal[] sma5 = calcSMA(closes, 5);
        BigDecimal[] sma10 = calcSMA(closes, 10);
        BigDecimal[] sma20 = calcSMA(closes, 20);
        BigDecimal[] sma60 = calcSMA(closes, 60);

        // ==================== 计算 MACD ====================
        BigDecimal[][] macdResult = calcMACD(closes);
        BigDecimal[] macdDifArr = macdResult[0];
        BigDecimal[] macdDeaArr = macdResult[1];
        BigDecimal[] macdBarArr = macdResult[2];

        // ==================== 计算 RSI ====================
        BigDecimal[] rsi6Arr = calcRSI(closes, RSI_PERIOD_6);
        BigDecimal[] rsi14Arr = calcRSI(closes, RSI_PERIOD_14);

        // ==================== 计算量比 ====================
        BigDecimal[] volRatioArr = calcVolRatio(volumes, VOL_MA_PERIOD);

        // ==================== 计算趋势斜率 ====================
        BigDecimal[] slopeArr = calcTrendSlope(closes);

        // ==================== 组装结果 ====================
        for (int i = 0; i < n; i++)
        {
            StockKline k = klines.get(i);
            StockIndicator indicator = new StockIndicator();
            indicator.setStockCode(k.getStockCode());
            indicator.setKlineType(k.getKlineType());
            indicator.setTradeTime(k.getTradeTime());
            indicator.setMa5(sma5[i]);
            indicator.setMa10(sma10[i]);
            indicator.setMa20(sma20[i]);
            indicator.setMa60(sma60[i]);
            indicator.setMacdDif(macdDifArr[i]);
            indicator.setMacdDea(macdDeaArr[i]);
            indicator.setMacdBar(macdBarArr[i]);
            indicator.setRsi6(rsi6Arr[i]);
            indicator.setRsi14(rsi14Arr[i]);
            indicator.setVolRatio(volRatioArr[i]);
            indicator.setTrendSlope(slopeArr[i]);
            results.add(indicator);
        }

        return results;
    }

    @Override
    public int batchSaveIndicators(List<StockIndicator> indicators)
    {
        if (indicators == null || indicators.isEmpty())
        {
            return 0;
        }
        return stockIndicatorMapper.batchInsertOrUpdate(indicators);
    }

    // ==================== 私有计算方法 ====================

    /**
     * 简单移动均线 SMA(N)
     */
    private BigDecimal[] calcSMA(List<BigDecimal> closes, int period)
    {
        int n = closes.size();
        BigDecimal[] result = new BigDecimal[n];

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++)
        {
            sum = sum.add(closes.get(i));
            if (i >= period - 1)
            {
                if (i >= period)
                {
                    sum = sum.subtract(closes.get(i - period));
                }
                result[i] = sum.divide(BigDecimal.valueOf(period), SCALE, RM);
            }
        }
        return result;
    }

    /**
     * 计算指数移动均值 EMA
     *
     * @param values  价格序列
     * @param period  周期
     * @param prevEma 前一期 EMA（seed 时为 null）
     * @param idx     当前索引
     * @return 当前 EMA，seed 时返回 close[idx]
     */
    private BigDecimal calcEMA(List<BigDecimal> values, int period, BigDecimal prevEma, int idx)
    {
        if (prevEma == null)
        {
            return values.get(idx);
        }
        BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(period + 1), SCALE, RM);
        // EMA = close * multiplier + prevEma * (1 - multiplier)
        return values.get(idx).multiply(multiplier)
                .add(prevEma.multiply(BigDecimal.ONE.subtract(multiplier)));
    }

    /**
     * MACD(12, 26, 9)
     *
     * @return [DIF数组, DEA数组, BAR数组]
     */
    private BigDecimal[][] calcMACD(List<BigDecimal> closes)
    {
        int n = closes.size();
        BigDecimal[] difArr = new BigDecimal[n];
        BigDecimal[] deaArr = new BigDecimal[n];
        BigDecimal[] barArr = new BigDecimal[n];

        // EMA_12 和 EMA_26 的缓存
        BigDecimal[] ema12 = new BigDecimal[n];
        BigDecimal[] ema26 = new BigDecimal[n];

        for (int i = 0; i < n; i++)
        {
            ema12[i] = calcEMA(closes, EMA_FAST, i > 0 ? ema12[i - 1] : null, i);
            ema26[i] = calcEMA(closes, EMA_SLOW, i > 0 ? ema26[i - 1] : null, i);

            // DIF = EMA12 - EMA26
            if (i >= EMA_SLOW - 1)
            {
                difArr[i] = ema12[i].subtract(ema26[i]);
            }
        }

        // DEA = 9日 EMA of DIF，BAR = 2 * (DIF - DEA)
        BigDecimal prevDea = null;
        for (int i = EMA_SLOW - 1; i < n; i++)
        {
            if (difArr[i] == null)
            {
                continue;
            }
            if (prevDea == null)
            {
                deaArr[i] = difArr[i];  // seed
            }
            else
            {
                // DEA[i] = DIF[i] * 2/(9+1) + DEA[i-1] * (1 - 2/(9+1))
                BigDecimal multiplier = TWO.divide(BigDecimal.valueOf(EMA_SIGNAL + 1), SCALE, RM);
                deaArr[i] = difArr[i].multiply(multiplier)
                        .add(deaArr[i - 1].multiply(BigDecimal.ONE.subtract(multiplier)));
            }
            prevDea = deaArr[i];

            if (deaArr[i] != null)
            {
                barArr[i] = difArr[i].subtract(deaArr[i]).multiply(TWO);
            }
        }

        return new BigDecimal[][]{ difArr, deaArr, barArr };
    }

    /**
     * RSI — Wilder's smoothed method
     *
     * 首期 AvgGain/AvgLoss = 简单平均；后续平滑：Avg = (prevAvg * (N-1) + current) / N
     */
    private BigDecimal[] calcRSI(List<BigDecimal> closes, int period)
    {
        int n = closes.size();
        BigDecimal[] result = new BigDecimal[n];

        if (n <= period)
        {
            return result;
        }

        // 首期：收集 period 根K线的涨跌
        BigDecimal sumGain = BigDecimal.ZERO;
        BigDecimal sumLoss = BigDecimal.ZERO;
        for (int i = 1; i <= period; i++)
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

        BigDecimal avgGain = sumGain.divide(BigDecimal.valueOf(period), SCALE, RM);
        BigDecimal avgLoss = sumLoss.divide(BigDecimal.valueOf(period), SCALE, RM);

        // 首期 RSI
        if (avgLoss.compareTo(BigDecimal.ZERO) == 0)
        {
            result[period] = HUNDRED;
        }
        else
        {
            BigDecimal rs = avgGain.divide(avgLoss, SCALE, RM);
            result[period] = HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), SCALE, RM));
        }

        // 后续各期平滑
        BigDecimal prevGain = avgGain;
        BigDecimal prevLoss = avgLoss;
        for (int i = period + 1; i < n; i++)
        {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal gain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

            prevGain = prevGain.multiply(BigDecimal.valueOf(period - 1)).add(gain)
                    .divide(BigDecimal.valueOf(period), SCALE, RM);
            prevLoss = prevLoss.multiply(BigDecimal.valueOf(period - 1)).add(loss)
                    .divide(BigDecimal.valueOf(period), SCALE, RM);

            if (prevLoss.compareTo(BigDecimal.ZERO) == 0)
            {
                result[i] = HUNDRED;
            }
            else
            {
                BigDecimal rs = prevGain.divide(prevLoss, SCALE, RM);
                result[i] = HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(rs), SCALE, RM));
            }
        }

        return result;
    }

    /**
     * 量比 = 当日成交量 / 5日均量
     */
    private BigDecimal[] calcVolRatio(List<Long> volumes, int period)
    {
        int n = volumes.size();
        BigDecimal[] result = new BigDecimal[n];

        for (int i = period - 1; i < n; i++)
        {
            long sum = 0;
            for (int j = i - period + 1; j <= i; j++)
            {
                sum += volumes.get(j);
            }
            BigDecimal avgVol = BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(period), SCALE, RM);
            if (avgVol.compareTo(BigDecimal.ZERO) > 0)
            {
                result[i] = BigDecimal.valueOf(volumes.get(i)).divide(avgVol, SCALE, RM);
            }
        }
        return result;
    }

    /**
     * 趋势斜率 — 线性回归（与 StockTrendReversalTask 使用相同的算法）
     */
    private BigDecimal[] calcTrendSlope(List<BigDecimal> closes)
    {
        int n = closes.size();
        BigDecimal[] result = new BigDecimal[n];

        // 只计算最后一个点的趋势斜率（使用全部数据），其余保持 null
        // 这样可减少计算量，且只需要最新斜率用于推荐判断
        if (n < 5)
        {
            return result;
        }

        BigDecimal sumX = BigDecimal.ZERO;
        BigDecimal sumY = BigDecimal.ZERO;
        BigDecimal sumXY = BigDecimal.ZERO;
        BigDecimal sumX2 = BigDecimal.ZERO;
        int m = n;

        for (int i = 0; i < m; i++)
        {
            BigDecimal xi = BigDecimal.valueOf(i);
            BigDecimal yi = closes.get(i);
            sumX = sumX.add(xi);
            sumY = sumY.add(yi);
            sumXY = sumXY.add(xi.multiply(yi));
            sumX2 = sumX2.add(xi.multiply(xi));
        }

        BigDecimal numerator = BigDecimal.valueOf(m).multiply(sumXY).subtract(sumX.multiply(sumY));
        BigDecimal denominator = BigDecimal.valueOf(m).multiply(sumX2).subtract(sumX.multiply(sumX));

        if (denominator.compareTo(BigDecimal.ZERO) != 0)
        {
            BigDecimal slope = numerator.divide(denominator, SCALE, RM);
            result[n - 1] = slope;
        }

        return result;
    }
}
