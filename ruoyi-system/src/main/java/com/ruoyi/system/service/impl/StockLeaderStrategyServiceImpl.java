package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.mapper.StockIndicatorMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockPlateMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockLeaderStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 龙头股七大战法推荐引擎实现
 *
 * 数据来源：财联社涨停记录（近15天）中，所属行业/概念为重点关注板块（focus_flag=1）的股票。
 * 均线（MA5/10/20/30/34/60）从 stock_indicator 缓存读取（由 StockIndicatorCalcTask 预计算），
 * 仅从 stock_kline 拉取原始 OHLCV 用于蜡烛形态/跳空/突破/前期涨停/振幅/新低等判定，避免重复现算指标。
 *
 * 战法一览：
 *   1 破位启动   2 暴力洗盘   3 慢牛提速   4 横盘出击
 *   5 跳空缺口   6 绝地反击   7 二次启动
 *
 * 每个战法识别「启动前的蓄势形态」（缩量假摔、跌停洗盘、慢牛蓄势、横盘缩量、
 * 跳空当日、超跌地量、回调企稳），输出「观察池」分组，分组备注标注对应的启动条件，
 * 供盘中人工观察启动信号后入场。
 *
 * 通用风控：排除 ST/*ST 股票。均线空头(MA5<MA10<MA20)过滤仅对偏多战法生效，
 * 绝地反击（超跌反转）为逆势战法，不受该过滤约束。
 */
@Service
public class StockLeaderStrategyServiceImpl implements IStockLeaderStrategyService
{
    private static final Logger log = LoggerFactory.getLogger(StockLeaderStrategyServiceImpl.class);

    /** 构建技术指标序列所需的最少K线数 */
    private static final int MIN_KLINES = 30;

    /** 双精度比较容差 */
    private static final double EPS = 1e-6;

    @Autowired
    private StockLimitUpRecordMapper stockLimitUpRecordMapper;

    @Autowired
    private StockPlateMapper stockPlateMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** K线/指标回看日历天数（覆盖约 100+ 交易日，满足 MA60 / 66日新低 / 40日窗口） */
    @Value("${stock.leader-strategy.lookback-days:150}")
    private int lookbackDays;

    /** 涨停候选回看日历天数（从财联社涨停记录中选取最近 N 天） */
    @Value("${stock.leader-strategy.limit-up-days:15}")
    private int limitUpDays;

    /**
     * 战法定义：名称 + 启动条件描述（写入分组备注） + 预告评估函数（左侧/蓄势）
     * 评估函数返回信号强度分数，null 表示未命中
     */
    private static class Strategy
    {
        final String name;
        final String trigger;
        final Function<StockSeries, Double> setup;

        Strategy(String name, String trigger, Function<StockSeries, Double> setup)
        {
            this.name = name;
            this.trigger = trigger;
            this.setup = setup;
        }
    }

    /**
     * 七大战法评估器（预告）
     */
    private Strategy[] strategies()
    {
        return new Strategy[]{
                new Strategy("破位启动", "出现≥5%放量大阳线拉回站上破位前高点", this::setup1),
                new Strategy("暴力洗盘", "出现涨停反包(实体>5%+放量)", this::setup2),
                new Strategy("慢牛提速", "放量突破近30日高点", this::setup3),
                new Strategy("横盘出击", "放量突破箱体最高价", this::setup4),
                new Strategy("跳空缺口", "连续3天不回补缺口", this::setup5),
                new Strategy("绝地反击", "出现放量长阳(高位收盘)", this::setup6),
                new Strategy("二次启动", "3倍放量突破前涨停收盘", this::setup7),
        };
    }

    @Override
    public void execute()
    {
        log.info("========== 龙头股七大战法推荐引擎开始 ==========");

        // 1. 从财联社涨停记录中选取最近 N 天的涨停股票，并校验其所属行业/概念是否为重点关注板块
        LocalDate today = LocalDate.now();
        String limitUpStartDate = TradingDayUtils.getDateStr(today.minusDays(limitUpDays));
        String limitUpEndDate = TradingDayUtils.getDateStr(today);
        List<String> limitUpCodes = stockLimitUpRecordMapper.selectLimitUpCodesInFocusPlate(limitUpStartDate, limitUpEndDate);
        if (limitUpCodes == null || limitUpCodes.isEmpty())
        {
            log.warn("最近 {} 天无「重点板块内」的涨停股票，跳过选股", limitUpDays);
            return;
        }
        log.info("最近 {} 天涨停且属于重点关注板块（行业/概念）的股票 {} 只", limitUpDays, limitUpCodes.size());

        // 2. 查询候选股票基础信息（跨板块去重）
        Map<String, Stock> candidateMap = new LinkedHashMap<>();
        List<Stock> stocks = stockPlateMapper.selectStocksByCodeBatch(limitUpCodes);
        if (stocks != null)
        {
            for (Stock s : stocks)
            {
                if (s.getStockCode() != null)
                {
                    candidateMap.put(s.getStockCode(), s);
                }
            }
        }
        if (candidateMap.isEmpty())
        {
            log.warn("涨停股票均无基础信息，跳过选股");
            return;
        }
        log.info("候选股票（去重后）共 {} 只", candidateMap.size());

        // 3. 批量拉取日K线（原始 OHLCV，用于形态/突破/振幅/新低等判定）
        List<String> codes = new ArrayList<>(candidateMap.keySet());
        String endDate = LocalDate.now().toString();
        String startDate = LocalDate.now().minusDays(lookbackDays).toString();
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate, endDate);
        log.info("K线数据总量: {}", allKlines != null ? allKlines.size() : 0);

        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));

        // 4. 批量拉取指标时序（由 StockIndicatorCalcTask 预计算，避免现算）
        List<StockIndicator> allIndicators = stockIndicatorMapper.selectByCodesAndTypeDateRange(
                codes, "D", startDate, endDate);
        log.info("指标数据总量: {}", allIndicators != null ? allIndicators.size() : 0);
        if (allIndicators == null || allIndicators.isEmpty())
        {
            log.warn("stock_indicator 无指标数据，请先运行 stockIndicatorCalcTask（含 KDJ/CCI/MA30/MA34 新列）");
        }

        Map<String, Map<LocalDate, StockIndicator>> indByCode = new HashMap<>();
        if (allIndicators != null)
        {
            for (StockIndicator ind : allIndicators)
            {
                if (ind.getStockCode() == null || ind.getTradeTime() == null)
                {
                    continue;
                }
                indByCode.computeIfAbsent(ind.getStockCode(), k -> new HashMap<>())
                        .put(toLocalDate(ind.getTradeTime()), ind);
            }
        }

        // 5. 构建每只股票的技术指标序列（OHLCV 来自K线，指标来自缓存）
        List<StockSeries> seriesList = new ArrayList<>();
        int skipped = 0;
        for (Stock s : candidateMap.values())
        {
            StockSeries series = buildSeries(s, klineMap.get(s.getStockCode()), indByCode.get(s.getStockCode()));
            if (series == null)
            {
                skipped++;
            }
            else
            {
                seriesList.add(series);
            }
        }
        log.info("可评估股票 {} 只，K线不足被跳过 {} 只", seriesList.size(), skipped);

        if (seriesList.isEmpty())
        {
            log.warn("无可用K线数据，任务结束");
            return;
        }

        // 6. 逐战法评估预告信号（观察池）
        Strategy[] strategies = strategies();
        String dateStr = LocalDate.now().toString();

        for (int i = 0; i < strategies.length; i++)
        {
            Strategy strategy = strategies[i];
            List<ScoredStock> setupMatched = new ArrayList<>();
            for (StockSeries series : seriesList)
            {
                Double setupScore = strategy.setup.apply(series);
                if (setupScore != null)
                {
                    setupMatched.add(new ScoredStock(series.stock.getId(), series.stock.getStockCode(),
                            series.stock.getStockName(), setupScore));
                }
            }

            // 7. 生成观察池分组（无命中则不生成）
            createGroup("战法" + (i + 1) + "_" + strategy.name + "_观察池_" + dateStr, setupMatched, strategy.trigger);
        }

        log.info("========== 龙头股七大战法推荐完成 ==========");
    }

    // ==================== 预告（左侧/蓄势）检测 ====================
    // 每个 setupX 识别「启动前的蓄势形态」，不要求大阳线/涨停/放量突破等结果，
    // 仅要求前置条件满足，输出「观察池」。启动条件由人工盘中观察。

    /**
     * 战法1 预告：缩量假摔破位（尚未拉回）
     * 近5日内出现「收盘破20日线且缩量」，且尚未出现5%大阳线拉回。
     */
    private Double setup1(StockSeries s)
    {
        if (s.n < 25) return null;
        for (int b = s.n - 2; b >= Math.max(s.n - 5, 0); b--)
        {
            double ma20b = s.ma20[b];
            if (Double.isNaN(ma20b)) continue;
            // 破位：收盘跌破20日均线
            if (s.close[b] >= ma20b) continue;
            // 缩量假摔：成交量不高于5日均量×1.1
            double volMa5b = s.volMa5[b];
            if (Double.isNaN(volMa5b) || s.vol[b] > volMa5b * 1.1) continue;
            // 尚未出现拉回大阳线（站上破位前高点）
            double peakBefore = max(s.high, 10, b - 1);
            boolean launched = false;
            for (int l = b + 1; l < s.n; l++)
            {
                if (s.close[l - 1] <= 0) continue;
                if (s.close[l] / s.close[l - 1] - 1 >= 0.05 && s.close[l] > peakBefore)
                {
                    launched = true;
                    break;
                }
            }
            if (launched) continue;
            return (ma20b - s.close[b]) / ma20b * 100;
        }
        return null;
    }

    /**
     * 战法2 预告：跌停/长阴洗盘当日（尚未反包）
     * 近5日内出现「跌≥7%长阴+放天量+均线多头」的洗盘日，且尚未出现涨停反包。
     */
    private Double setup2(StockSeries s)
    {
        if (s.n < 45) return null;
        if (hasThreeConsecutiveLimitUp(s, 40)) return null;
        for (int w = s.n - 2; w >= Math.max(s.n - 5, 1); w--)
        {
            if (s.close[w - 1] <= 0 || s.open[w] <= 0) continue;
            double drop = s.close[w] / s.close[w - 1] - 1;
            double body = (s.open[w] - s.close[w]) / s.open[w];
            if (drop > -0.07) continue;
            if (body <= 0.05) continue;
            double lowerShadow = Math.min(s.open[w], s.close[w]) - s.low[w];
            if (lowerShadow > (s.open[w] - s.close[w]) * 0.5) continue;
            int before = w - 1;
            if (Double.isNaN(s.ma5[before]) || Double.isNaN(s.ma10[before]) || Double.isNaN(s.ma20[before])) continue;
            if (!(s.ma5[before] > s.ma10[before] && s.ma10[before] > s.ma20[before])) continue;
            if (hasLimitUpInRange(s, w - 10, w - 1)) continue;
            if (s.vol[w] < max(s.vol, 10, w - 1)) continue;
            // 尚未出现涨停反包
            boolean reversed = false;
            for (int r = w + 1; r < s.n; r++)
            {
                if (s.close[r - 1] <= 0) continue;
                if (s.close[r] / s.close[r - 1] - 1 >= s.limitUp * 0.98) { reversed = true; break; }
            }
            if (reversed) continue;
            return -drop * 100;
        }
        return null;
    }

    /**
     * 战法3 预告：慢牛蓄势（尚未突破）
     * 均线多头+乖离率小+小阴小阳，距近30日高点≤5%但尚未突破。
     */
    private Double setup3(StockSeries s)
    {
        if (s.n < 65) return null;
        int i = s.n - 1;
        if (Double.isNaN(s.ma5[i]) || Double.isNaN(s.ma10[i]) || Double.isNaN(s.ma20[i])
                || Double.isNaN(s.ma30[i]) || Double.isNaN(s.ma60[i])) return null;
        if (!(s.ma5[i] > s.ma10[i] && s.ma10[i] > s.ma20[i]
                && s.ma20[i] > s.ma30[i] && s.ma30[i] > s.ma60[i])) return null;
        if (Math.abs(s.close[i] / s.ma5[i] - 1) > 0.03) return null;
        double sumAbs = 0;
        int cnt = 0;
        for (int t = i - 9; t <= i - 1; t++)
        {
            if (t - 1 < 0 || s.close[t - 1] <= 0) continue;
            sumAbs += Math.abs(s.close[t] / s.close[t - 1] - 1);
            cnt++;
        }
        if (cnt == 0 || sumAbs / cnt > 0.03) return null;
        // 尚未突破近30日高点，且距高点≤5%
        double recentHigh = max(s.high, 30, i - 1);
        if (s.close[i] > recentHigh) return null;
        double room = (recentHigh - s.close[i]) / recentHigh;
        if (room > 0.05) return null;
        return (1 - room) * 100;
    }

    /**
     * 战法4 预告：横盘缩量蓄势（尚未突破）
     * 近20日振幅≤10%，当日缩量（<20日均量），收盘仍在箱体内。
     */
    private Double setup4(StockSeries s)
    {
        if (s.n < 25) return null;
        int i = s.n - 1;
        double high20 = max(s.high, 20, i - 1);
        double low20 = min(s.low, 20, i - 1);
        if (low20 <= 0) return null;
        double range = (high20 - low20) / low20;
        if (range > 0.10) return null;
        // 尚未突破
        if (s.close[i] > high20) return null;
        // 缩量蓄势：当日量 < 20日均量
        double volMa20 = s.volMa20[i];
        if (Double.isNaN(volMa20) || volMa20 <= 0) return null;
        if (s.vol[i] > volMa20) return null;
        // 箱体越紧凑，蓄势越强
        return (0.10 - range) / 0.10 * 50;
    }

    /**
     * 战法5 预告：跳空当日/次日（尚未确认不回补）
     * 近2日内出现「≥2%跳空+倍量」，当前低点尚未回补缺口。
     */
    private Double setup5(StockSeries s)
    {
        if (s.n < 15) return null;
        for (int g = s.n - 2; g >= Math.max(s.n - 3, 1); g--)
        {
            if (s.high[g - 1] <= 0) continue;
            double gap = s.open[g] / s.high[g - 1] - 1;
            if (gap < 0.02) continue;
            if (s.vol[g] < s.vol[g - 1] * 2) continue;
            // 尚未回补（当前低点仍高于跳空前高点）
            if (s.low[s.n - 1] <= s.high[g - 1]) continue;
            return gap * 100;
        }
        return null;
    }

    /**
     * 战法6 预告：超跌地量（尚未长阳）
     * 低于34日线14%且创66日新低，当日缩量（<5日均量×0.8），长阳尚未出现。
     */
    private Double setup6(StockSeries s)
    {
        if (s.n < 70) return null;
        int i = s.n - 1;
        double ma34 = s.ma34[i];
        if (Double.isNaN(ma34) || ma34 <= 0) return null;
        if (s.close[i] >= ma34 * 0.86) return null;
        if (s.low[i] > min(s.low, 66, i) + EPS) return null;
        // 地量：当日量 < 5日均量×0.8
        double volMa5 = s.volMa5[i];
        if (Double.isNaN(volMa5) || volMa5 <= 0) return null;
        if (s.vol[i] > volMa5 * 0.8) return null;
        // 尚未出现长阳
        if (s.close[i - 1] <= 0) return null;
        if (s.close[i] / s.close[i - 1] - 1 > 0.03) return null;
        return (ma34 - s.close[i]) / ma34 * 100;
    }

    /**
     * 战法7 预告：回调缩量企稳（尚未突破）
     * 前涨停后回调不破涨停最低、缩量、贴近5日线，但尚未放量突破前涨停收盘。
     */
    private Double setup7(StockSeries s)
    {
        if (s.n < 45) return null;
        int i = s.n - 1;
        for (int p = Math.max(s.n - 40, 1); p <= i - 2; p++)
        {
            if (s.close[p - 1] <= 0) continue;
            double gain = s.close[p] / s.close[p - 1] - 1;
            if (gain <= s.limitUp * 0.98) continue;
            if (!upperClose(s, p)) continue;
            int lo = p + 2;
            int hi = Math.min(p + 28, i);
            if (lo > hi) continue;
            double hh = max(s.high, hi - lo + 1, hi);
            double ll = min(s.low, hi - lo + 1, hi);
            if (ll <= 0 || (hh - ll) / ll > 0.15) continue;
            if (ll < s.low[p] - EPS) continue;
            // 尚未突破前涨停收盘（仍在回调企稳）
            if (s.close[i] > s.close[p]) continue;
            // 缩量企稳：当日量 < 5日均量
            double volMa5 = s.volMa5[i];
            if (Double.isNaN(volMa5) || s.vol[i] > volMa5) continue;
            // 贴近5日线（不深跌）
            if (Double.isNaN(s.ma5[i]) || s.ma5[i] <= 0) continue;
            if (s.close[i] < s.ma5[i] * 0.97) continue;
            return 60.0;
        }
        return null;
    }

    // ==================== 辅助判定 ====================

    /**
     * 区间 [start, end] 内是否存在涨停（涨幅≥9.5%）
     */
    private boolean hasLimitUpInRange(StockSeries s, int start, int end)
    {
        start = Math.max(start, 1);
        for (int t = start; t <= end && t < s.n; t++)
        {
            if (s.close[t - 1] <= 0) continue;
            if (s.close[t] / s.close[t - 1] - 1 >= s.limitUp * 0.98) return true;
        }
        return false;
    }

    /**
     * 最近 lookback 个交易日内是否出现过三连板
     */
    private boolean hasThreeConsecutiveLimitUp(StockSeries s, int lookback)
    {
        int start = Math.max(s.n - lookback, 1);
        int streak = 0;
        for (int t = start; t < s.n; t++)
        {
            if (s.close[t - 1] <= 0)
            {
                streak = 0;
                continue;
            }
            if (s.close[t] / s.close[t - 1] - 1 >= s.limitUp * 0.98)
            {
                streak++;
                if (streak >= 3) return true;
            }
            else
            {
                streak = 0;
            }
        }
        return false;
    }

    // ==================== 指标序列工具 ====================

    /** 简单移动平均（成交量均线等轻量计算） */
    private static double[] ma(double[] src, int period)
    {
        int n = src.length;
        double[] out = new double[n];
        Arrays.fill(out, Double.NaN);
        double sum = 0;
        for (int i = 0; i < n; i++)
        {
            sum += src[i];
            if (i >= period) sum -= src[i - period];
            if (i >= period - 1) out[i] = sum / period;
        }
        return out;
    }

    /** 区间 [endIdx-period+1, endIdx] 最大值 */
    private static double max(double[] a, int period, int endIdx)
    {
        double m = Double.NEGATIVE_INFINITY;
        int start = Math.max(0, endIdx - period + 1);
        for (int i = start; i <= endIdx && i < a.length; i++)
        {
            if (!Double.isNaN(a[i])) m = Math.max(m, a[i]);
        }
        return m;
    }

    /** 区间 [endIdx-period+1, endIdx] 最小值 */
    private static double min(double[] a, int period, int endIdx)
    {
        double m = Double.POSITIVE_INFINITY;
        int start = Math.max(0, endIdx - period + 1);
        for (int i = start; i <= endIdx && i < a.length; i++)
        {
            if (!Double.isNaN(a[i])) m = Math.min(m, a[i]);
        }
        return m;
    }

    /** BigDecimal -> double，null 返回 NaN */
    private static double val(BigDecimal b)
    {
        return b == null ? Double.NaN : b.doubleValue();
    }

    /** Date -> LocalDate（按系统默认时区） */
    private static LocalDate toLocalDate(Date d)
    {
        if (d == null) return null;
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * 板块自适应涨停幅度：北交所30% / 创业板·科创板20% / 主板ST 5% / 主板10%
     */
    private static double limitUpPct(Stock stock)
    {
        String market = stock.getMarket();
        String code = stock.getStockCode();
        String name = stock.getStockName();
        if ("BJ".equalsIgnoreCase(market)) return 0.30;
        if (code != null && (code.startsWith("688") || code.startsWith("30"))) return 0.20;
        if (name != null && name.toUpperCase().contains("ST")) return 0.05;
        return 0.10;
    }

    /**
     * 高位收盘（近似光头阳线）：收盘价位于当日振幅上端 80% 以上
     */
    private static boolean upperClose(StockSeries s, int idx)
    {
        double range = s.high[idx] - s.low[idx];
        if (range <= 0) return false;
        return (s.close[idx] - s.low[idx]) / range >= 0.8;
    }

    /**
     * 生成一个观察池分组：按分数降序，命中全部纳入；无命中则不生成
     */
    private void createGroup(String groupName, List<ScoredStock> matched, String trigger)
    {
        // 无命中则不生成分组
        if (matched.isEmpty())
        {
            log.info("观察池 [{}] 无命中，跳过生成", groupName);
            return;
        }

        matched.sort(Comparator.comparingDouble(ScoredStock::getScore).reversed());

        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockCodes(matched.stream().map(ScoredStock::getStockCode).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("观察启动条件：%s（命中%d只）", trigger, matched.size()));

        stockGroupService.insertGroup(group);

        log.info("观察池分组 [{}] 命中 {} 只，全部推荐", groupName, matched.size());
        for (ScoredStock ss : matched)
        {
            log.info("    {} {} 评分={}", ss.stockCode, ss.stockName, Math.round(ss.score));
        }
    }

    // ==================== 数据结构 ====================

    /**
     * 单只股票的技术指标序列：OHLCV 来自K线，MA 来自缓存，量均线由成交量现算
     */
    private static class StockSeries
    {
        final Stock stock;
        final int n;
        final double[] close;
        final double[] open;
        final double[] high;
        final double[] low;
        final double[] vol;
        final double[] volMa5;
        final double[] volMa20;
        final double limitUp;

        double[] ma5, ma10, ma20, ma30, ma34, ma60;

        StockSeries(Stock stock, double[] open, double[] close, double[] high, double[] low, double[] vol)
        {
            this.stock = stock;
            this.open = open;
            this.close = close;
            this.high = high;
            this.low = low;
            this.vol = vol;
            this.n = close.length;
            this.volMa5 = ma(vol, 5);
            this.volMa20 = ma(vol, 20);
            this.limitUp = limitUpPct(stock);
        }
    }

    /**
     * 评分结果
     */
    private static class ScoredStock
    {
        final Long id;
        final String stockCode;
        final String stockName;
        final double score;

        ScoredStock(Long id, String stockCode, String stockName, double score)
        {
            this.id = id;
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.score = score;
        }

        Long getId() { return id; }
        String getStockCode() { return stockCode; }
        String getStockName() { return stockName; }
        double getScore() { return score; }
    }

    /**
     * 从K线 + 指标缓存构建技术指标序列，K线不足或无效时返回 null
     *
     * @param indByDate 该股票按日期索引的指标缓存（可为 null/空，缺失处指标为 NaN）
     */
    private StockSeries buildSeries(Stock stock, List<StockKline> rawKlines, Map<LocalDate, StockIndicator> indByDate)
    {
        if (stock == null || rawKlines == null || rawKlines.isEmpty())
        {
            return null;
        }
        // 排除 ST/*ST
        if (stock.getStockName() != null && stock.getStockName().toUpperCase().contains("ST"))
        {
            return null;
        }

        List<StockKline> list = new ArrayList<>(rawKlines);
        list.sort(Comparator.comparing(StockKline::getTradeTime));

        List<Double> o = new ArrayList<>(), c = new ArrayList<>(), h = new ArrayList<>(), l = new ArrayList<>(), v = new ArrayList<>();
        List<LocalDate> dates = new ArrayList<>();
        for (StockKline kl : list)
        {
            if (kl.getClosePrice() == null || kl.getHighPrice() == null || kl.getLowPrice() == null)
            {
                continue;
            }
            double close = kl.getClosePrice().doubleValue();
            o.add(kl.getOpenPrice() == null ? close : kl.getOpenPrice().doubleValue());
            c.add(close);
            h.add(kl.getHighPrice().doubleValue());
            l.add(kl.getLowPrice().doubleValue());
            v.add(kl.getVolume() == null ? 0d : kl.getVolume().doubleValue());
            dates.add(toLocalDate(kl.getTradeTime()));
        }
        if (c.size() < MIN_KLINES)
        {
            return null;
        }

        StockSeries s = new StockSeries(stock, toArray(o), toArray(c), toArray(h), toArray(l), toArray(v));

        // 初始化指标数组为 NaN，再按日期从缓存填充
        int n = s.n;
        s.ma5 = new double[n]; s.ma10 = new double[n]; s.ma20 = new double[n];
        s.ma30 = new double[n]; s.ma34 = new double[n]; s.ma60 = new double[n];
        Arrays.fill(s.ma5, Double.NaN); Arrays.fill(s.ma10, Double.NaN); Arrays.fill(s.ma20, Double.NaN);
        Arrays.fill(s.ma30, Double.NaN); Arrays.fill(s.ma34, Double.NaN); Arrays.fill(s.ma60, Double.NaN);

        if (indByDate != null && !indByDate.isEmpty())
        {
            for (int i = 0; i < n; i++)
            {
                LocalDate date = dates.get(i);
                if (date == null) continue;
                StockIndicator ind = indByDate.get(date);
                if (ind == null) continue;
                s.ma5[i] = val(ind.getMa5());
                s.ma10[i] = val(ind.getMa10());
                s.ma20[i] = val(ind.getMa20());
                s.ma30[i] = val(ind.getMa30());
                s.ma34[i] = val(ind.getMa34());
                s.ma60[i] = val(ind.getMa60());
            }
        }
        return s;
    }

    private static double[] toArray(List<Double> list)
    {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
