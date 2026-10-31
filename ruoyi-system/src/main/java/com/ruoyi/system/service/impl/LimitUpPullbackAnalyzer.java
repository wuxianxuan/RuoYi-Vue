package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 首二板回调分时分析器
 *
 * 从原 StockLimitUpPullbackServiceImpl 抽出的纯分析逻辑，供 117 对推荐池中的
 * LIMIT_UP 来源候选套用「回调承接」规则，不再单独占用一个 14:46 的定时任务。
 *
 * @author ruoyi
 */
@Component
public class LimitUpPullbackAnalyzer
{
    private static final Logger log = LoggerFactory.getLogger(LimitUpPullbackAnalyzer.class);

    private static final double YI = 100000000d;

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    @Value("${stock.limit-up-pullback.minute-type:m5}")
    private String minuteType;

    @Value("${stock.limit-up-pullback.min-pullback:-4.0}")
    private double minPullback;

    @Value("${stock.limit-up-pullback.max-pullback:1.5}")
    private double maxPullback;

    @Value("${stock.limit-up-pullback.min-close-pos:0.45}")
    private double minClosePos;

    @Value("${stock.limit-up-pullback.min-vwap-dev:-0.5}")
    private double minVwapDev;

    @Value("${stock.limit-up-pullback.min-tail-change:-0.5}")
    private double minTailChange;

    @Value("${stock.limit-up-pullback.max-limit-up-amplitude:12.0}")
    private double maxLimitUpAmplitude;

    @Value("${stock.limit-up-pullback.exclude-one-line:true}")
    private boolean excludeOneLine;

    @Value("${stock.limit-up-pullback.min-cap-yi:30}")
    private double minCapYi;

    @Value("${stock.limit-up-pullback.min-turnover-rate:3.0}")
    private double minTurnoverRate;

    @Value("${stock.limit-up-pullback.max-turnover-rate:20.0}")
    private double maxTurnoverRate;

    @Value("${stock.limit-up-pullback.tail-bars:6}")
    private int tailBars;

    /**
     * 分析单只涨停首二板候选
     *
     * @param code    股票代码
     * @param endDate 分析日
     * @param dayBar  涨停日（前一交易日）日K
     * @param stock   股票快照
     * @param rejects 淘汰原因计数
     * @return 通过返回结果，否则 null（原因已计入 rejects）
     */
    public PullbackResult analyze(String code, LocalDate endDate, StockKline dayBar, Stock stock,
                                  Map<String, AtomicInteger> rejects)
    {
        try
        {
            if (dayBar == null || dayBar.getClosePrice() == null)
            {
                return reject(rejects, "无昨日K线");
            }
            TencentKlineDataSource.MinuteSnapshot snap = tencentKlineDataSource.fetchMinuteSnapshot(
                    code, inferMarket(code), minuteType, endDate.toString());
            List<StockKline> bars = snap.getBars() == null ? new ArrayList<>() : snap.getBars().stream()
                    .filter(k -> k.getTradeTime() != null && k.getClosePrice() != null
                            && k.getHighPrice() != null && k.getLowPrice() != null)
                    .sorted(Comparator.comparing(StockKline::getTradeTime))
                    .collect(Collectors.toList());
            if (bars.size() < tailBars + 1)
            {
                return reject(rejects, "数据不足");
            }

            double prevClose = dayBar.getClosePrice().doubleValue();
            if (prevClose <= 0)
            {
                return reject(rejects, "无昨收");
            }

            StockKline last = bars.get(bars.size() - 1);
            double price = last.getClosePrice().doubleValue();
            double dayHigh = -Double.MAX_VALUE;
            double dayLow = Double.MAX_VALUE;
            double pv = 0;
            double vv = 0;
            for (StockKline k : bars)
            {
                dayHigh = Math.max(dayHigh, k.getHighPrice().doubleValue());
                dayLow = Math.min(dayLow, k.getLowPrice().doubleValue());
                double v = k.getVolume() == null ? 0 : k.getVolume();
                pv += k.getClosePrice().doubleValue() * v;
                vv += v;
            }
            if (!(dayHigh > dayLow) || vv <= 0)
            {
                return reject(rejects, "数据不足");
            }
            double vwap = pv / vv;
            double pullback = (price - prevClose) / prevClose * 100;
            double closePos = (price - dayLow) / (dayHigh - dayLow);
            double vwapDev = (price - vwap) / vwap * 100;

            double tailStart = bars.get(bars.size() - tailBars - 1).getClosePrice().doubleValue();
            double tailChange = tailStart > 0 ? (price - tailStart) / tailStart * 100 : 0;

            double dHigh = dayBar.getHighPrice() == null ? 0 : dayBar.getHighPrice().doubleValue();
            double dLow = dayBar.getLowPrice() == null ? 0 : dayBar.getLowPrice().doubleValue();
            double dAmp = prevClose > 0 ? (dHigh - dLow) / prevClose * 100 : 0;
            boolean oneLine = dHigh > 0 && Math.abs(dHigh - dLow) < 1e-6;
            double capYi = stock != null && stock.getCircMarketCap() != null
                    ? stock.getCircMarketCap().doubleValue() / YI : 0;

            Double turnover = snap.getTurnoverRate();
            if (turnover == null && stock != null && stock.getCirculatingShares() != null
                    && stock.getCirculatingShares().doubleValue() > 0 && vv > 0)
            {
                turnover = vv * 100.0 / stock.getCirculatingShares().doubleValue();
            }

            if (excludeOneLine && oneLine)
            {
                return reject(rejects, "一字板");
            }
            if (dAmp > maxLimitUpAmplitude)
            {
                return reject(rejects, "涨停日振幅过大");
            }
            if (capYi > 0 && capYi < minCapYi)
            {
                return reject(rejects, "市值过小");
            }
            if (pullback < minPullback)
            {
                return reject(rejects, "深跌不接");
            }
            if (pullback > maxPullback)
            {
                return reject(rejects, "未回调");
            }
            if (turnover != null && (turnover < minTurnoverRate || turnover > maxTurnoverRate))
            {
                return reject(rejects, "换手不达标");
            }
            if (closePos < minClosePos)
            {
                return reject(rejects, "收盘位置偏低");
            }
            if (vwapDev < minVwapDev)
            {
                return reject(rejects, "弱于分时均价");
            }
            if (tailChange < minTailChange)
            {
                return reject(rejects, "尾盘跳水");
            }

            double vwapScore = clamp01(1 + vwapDev / 1.5);
            double pullbackScore = clamp01(1 - Math.abs(pullback + 1.5) / 3.0);
            double tailScore = clamp01((tailChange + 0.5) / 2.0);
            double turnScore = turnover == null ? 0.5 : clamp01(1 - Math.abs(turnover - 6.0) / 10.0);
            double score = 0.30 * closePos + 0.25 * vwapScore + 0.20 * pullbackScore
                    + 0.15 * tailScore + 0.10 * turnScore;

            PullbackResult result = new PullbackResult();
            result.code = code;
            result.name = stock == null ? code : stock.getStockName();
            result.price = price;
            result.prevClose = prevClose;
            result.pullback = pullback;
            result.closePos = closePos;
            result.vwapDev = vwapDev;
            result.tailChange = tailChange;
            result.turnover = turnover;
            result.score = score;
            return result;
        }
        catch (Exception e)
        {
            log.debug("首二板分析失败 code={}, error={}", code, e.getMessage());
            return reject(rejects, "抓取失败");
        }
    }

    private PullbackResult reject(Map<String, AtomicInteger> rejects, String reason)
    {
        rejects.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
        return null;
    }

    private double clamp01(double value)
    {
        return Math.max(0, Math.min(1, value));
    }

    private String inferMarket(String code)
    {
        if (code == null || code.length() < 6)
        {
            return "SZ";
        }
        char first = code.charAt(0);
        if (first == '6' || first == '9')
        {
            return "SH";
        }
        return "SZ";
    }

    /**
     * 首二板回调分析结果
     */
    public static class PullbackResult
    {
        public String code;
        public String name;
        public double price;
        public double prevClose;
        public double pullback;
        public double closePos;
        public double vwapDev;
        public double tailChange;
        public Double turnover;
        public double score;
    }
}
