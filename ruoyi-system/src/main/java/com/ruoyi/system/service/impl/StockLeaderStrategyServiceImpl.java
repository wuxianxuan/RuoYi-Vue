package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockIndicatorMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockLeaderStrategyService;
import com.ruoyi.system.service.IStockPlateService;
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
 * 数据来源：focus_flag=1 的重点关注板块（行业+概念）成分股。
 * 技术指标（MA/KDJ/CCI/RSI/MACD）直接从 stock_indicator 缓存读取（由
 * StockIndicatorCalcTask 预计算），仅从 stock_kline 拉取原始 OHLCV 用于
 * 蜡烛形态/跳空/突破/前期涨停/振幅/新低等判定，避免重复现算指标。
 *
 * 战法一览：
 *   1 破位启动   2 暴力洗盘   3 慢牛提速   4 横盘出击
 *   5 跳空缺口   6 绝地反击   7 二次启动
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
    private IStockPlateService stockPlateService;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private StockIndicatorMapper stockIndicatorMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** 每个战法分组最多取几只股票 */
    @Value("${stock.leader-strategy.top-stocks:10}")
    private int topStocks;

    /** K线/指标回看日历天数（覆盖约 100+ 交易日，满足 MA60 / 66日新低 / 40日窗口） */
    @Value("${stock.leader-strategy.lookback-days:150}")
    private int lookbackDays;

    /**
     * 战法定义：名称 + 评估函数（返回信号强度分数，null 表示未命中）
     */
    private static class Strategy
    {
        final String name;
        final Function<StockSeries, Double> eval;

        Strategy(String name, Function<StockSeries, Double> eval)
        {
            this.name = name;
            this.eval = eval;
        }
    }

    /**
     * 七大战法评估器
     */
    private Strategy[] strategies()
    {
        return new Strategy[]{
                new Strategy("破位启动", this::eval1),
                new Strategy("暴力洗盘", this::eval2),
                new Strategy("慢牛提速", this::eval3),
                new Strategy("横盘出击", this::eval4),
                new Strategy("跳空缺口", this::eval5),
                new Strategy("绝地反击", this::eval6),
                new Strategy("二次启动", this::eval7),
        };
    }

    @Override
    public void execute()
    {
        log.info("========== 龙头股七大战法推荐引擎开始 ==========");

        // 1. 查询重点关注板块（focus_flag = 1，含行业+概念）
        StockPlate filter = new StockPlate();
        filter.setFocusFlag(1);
        List<StockPlate> plates = stockPlateService.selectPlateList(filter);
        if (plates == null || plates.isEmpty())
        {
            log.warn("无重点关注板块（focus_flag=1），跳过选股");
            return;
        }
        log.info("重点关注板块（行业+概念）共 {} 个", plates.size());

        // 2. 汇总候选股票（跨板块去重）
        Map<String, Stock> candidateMap = new LinkedHashMap<>();
        for (StockPlate plate : plates)
        {
            List<Stock> stocks = stockPlateService.selectPlateStocks(plate.getId());
            if (stocks == null)
            {
                continue;
            }
            for (Stock s : stocks)
            {
                if (s.getStockCode() != null)
                {
                    candidateMap.putIfAbsent(s.getStockCode(), s);
                }
            }
        }
        if (candidateMap.isEmpty())
        {
            log.warn("重点关注板块下无成分股，跳过选股");
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

        // 6. 逐战法评估并排序
        Strategy[] strategies = strategies();
        String dateStr = LocalDate.now().toString();

        for (int i = 0; i < strategies.length; i++)
        {
            Strategy strategy = strategies[i];
            List<ScoredStock> matched = new ArrayList<>();
            for (StockSeries series : seriesList)
            {
                Double score = strategy.eval.apply(series);
                if (score != null)
                {
                    matched.add(new ScoredStock(series.stock.getId(), series.stock.getStockCode(),
                            series.stock.getStockName(), score));
                }
            }
            matched.sort(Comparator.comparingDouble(ScoredStock::getScore).reversed());

            // 7. 生成分组（每个战法一个分组，命中为空时也创建空分组，保证 7 个分组）
            int topN = Math.min(topStocks, matched.size());
            List<ScoredStock> top = matched.subList(0, topN);

            StockGroup group = new StockGroup();
            group.setGroupName("战法" + (i + 1) + "_" + strategy.name + "_" + dateStr);
            group.setStockIds(top.stream().map(ScoredStock::getId).toArray(Long[]::new));
            group.setCreateBy("system");
            group.setRemark(matched.isEmpty()
                    ? "今日无命中"
                    : String.format("命中%d只 最高分%.0f", matched.size(), top.get(0).score));

            stockGroupService.insertGroup(group);

            if (matched.isEmpty())
            {
                log.info("战法{} [{}] 无命中，创建空分组", i + 1, strategy.name);
            }
            else
            {
                log.info("战法{} [{}] 命中 {} 只，取前 {} 只生成分组: [{}]",
                        i + 1, strategy.name, matched.size(), topN, group.getGroupName());
                for (ScoredStock ss : top)
                {
                    log.info("    {} {} 评分={}", ss.stockCode, ss.stockName, Math.round(ss.score));
                }
            }
        }

        log.info("========== 龙头股七大战法推荐完成 ==========");
    }

    // ==================== 战法一：破位启动 ====================

    /**
     * 主力砸破20日均线制造恐慌（假摔缩量），随后放量大阳线拉回并突破。
     * 破位日缩量，启动日涨幅≥5%且站上破位前高点、放量确认，KDJ D<80 且 D/J 向上拐头，MACD 金叉。
     */
    private Double eval1(StockSeries s)
    {
        if (s.n < 25) return null;
        for (int b = Math.max(s.n - 12, 0); b < s.n - 1; b++)
        {
            double ma20b = s.ma20[b];
            if (Double.isNaN(ma20b)) continue;
            // 破位日：收盘价跌破20日均线
            if (s.close[b] >= ma20b) continue;
            // 破位日缩量（假摔）：成交量不高于近5日均量，抛压不重
            double volMa5b = s.volMa5[b];
            if (Double.isNaN(volMa5b) || s.vol[b] > volMa5b * 1.1) continue;
            // 破位前高点：破位日往前10日最高价
            double peakBefore = max(s.high, 10, b - 1);

            for (int l = b + 1; l <= Math.min(b + 5, s.n - 1); l++)
            {
                if (s.close[l - 1] <= 0) continue;
                double gain = s.close[l] / s.close[l - 1] - 1;
                // 启动日：涨幅≥5%
                if (gain < 0.05) continue;
                // 收盘价重新站上破位前高点
                if (s.close[l] <= peakBefore) continue;
                // KDJ：D<80，且 D、J 向上拐头
                if (Double.isNaN(s.d[l])) continue;
                if (s.d[l] >= 80) continue;
                if (l - 1 >= 0 && s.d[l] <= s.d[l - 1]) continue;
                if (l - 1 >= 0 && s.j[l] <= s.j[l - 1]) continue;
                // 启动日放量确认（较前一日放量 且 ≥5日均量）
                if (s.vol[l] < s.vol[l - 1] * 1.2) continue;
                if (Double.isNaN(s.volMa5[l]) || s.vol[l] < s.volMa5[l] * 1.2) continue;
                // MACD 零轴附近金叉（DIF 上穿 DEA）
                if (Double.isNaN(s.dif[l]) || Double.isNaN(s.dea[l]) || s.dif[l] <= s.dea[l]) continue;
                return gain * 100;
            }
        }
        return null;
    }

    // ==================== 战法二：暴力洗盘 ====================

    /**
     * 长阴线/跌停清洗浮筹后快速反转。洗盘日跌幅≥7%且阴线实体>5%、无长下影，
     * 反转日出现实体>5%的饱满涨停阳线（涨停幅度按板块自适应）且放量确认。
     * 过滤：过去40日内无三连板、洗盘前10日无涨停、洗盘前均线多头排列。
     */
    private Double eval2(StockSeries s)
    {
        if (s.n < 45) return null;
        // 关键过滤：过去40个交易日从未出现三连板
        if (hasThreeConsecutiveLimitUp(s, 40)) return null;

        for (int w = Math.max(s.n - 10, 1); w < s.n - 1; w++)
        {
            if (s.close[w - 1] <= 0 || s.open[w] <= 0) continue;
            double drop = s.close[w] / s.close[w - 1] - 1;
            double body = (s.open[w] - s.close[w]) / s.open[w];
            // 洗盘阴线：单日跌幅≥7%，实体>5%
            if (drop > -0.07) continue;
            if (body <= 0.05) continue;
            // 无长下影护盘（下影线 < 实体50%）
            double lowerShadow = Math.min(s.open[w], s.close[w]) - s.low[w];
            if (lowerShadow > (s.open[w] - s.close[w]) * 0.5) continue;
            // 洗盘前均线多头排列
            int before = w - 1;
            if (Double.isNaN(s.ma5[before]) || Double.isNaN(s.ma10[before]) || Double.isNaN(s.ma20[before])) continue;
            if (!(s.ma5[before] > s.ma10[before] && s.ma10[before] > s.ma20[before])) continue;
            // 洗盘前10个交易日内无涨停板
            if (hasLimitUpInRange(s, w - 10, w - 1)) continue;
            // 洗盘日放阶段天量（近10日最高，不含当日）
            if (s.vol[w] < max(s.vol, 10, w - 1)) continue;

            // 反转日：洗盘后1~3日
            for (int r = w + 1; r <= Math.min(w + 3, s.n - 1); r++)
            {
                if (s.close[r - 1] <= 0 || s.open[r] <= 0) continue;
                double gain = s.close[r] / s.close[r - 1] - 1;
                double bodyR = (s.close[r] - s.open[r]) / s.open[r];
                // 实体>5%的饱满涨停阳线（涨停幅度按板块自适应）
                if (gain < s.limitUp * 0.98) continue;
                if (bodyR <= 0.05) continue;
                // 反转日放量确认
                if (s.vol[r] < s.vol[r - 1] * 1.2) continue;
                return gain * 100;
            }
        }
        return null;
    }

    // ==================== 战法三：慢牛提速 ====================

    /**
     * 沿均线缓慢攀升后突然加速。均线5/10/20/30/60多头排列、5日乖离率小、
     * 慢牛期小阴小阳，加速日突破近30日高点且量比≥1.5倍5日均量。
     * （「回档三五线」以慢牛期小实体、温和量能近似替代）
     */
    private Double eval3(StockSeries s)
    {
        if (s.n < 65) return null;
        int i = s.n - 1;
        // 均线多头排列 5>10>20>30>60
        if (Double.isNaN(s.ma5[i]) || Double.isNaN(s.ma10[i]) || Double.isNaN(s.ma20[i])
                || Double.isNaN(s.ma30[i]) || Double.isNaN(s.ma60[i])) return null;
        if (!(s.ma5[i] > s.ma10[i] && s.ma10[i] > s.ma20[i]
                && s.ma20[i] > s.ma30[i] && s.ma30[i] > s.ma60[i])) return null;
        // 5日均线乖离率小（慢牛特征）：|close/MA5 - 1| < 3%
        if (Math.abs(s.close[i] / s.ma5[i] - 1) > 0.03) return null;
        // 慢牛期：过去10日小阴小阳（平均涨跌幅绝对值 < 3%）
        double sumAbs = 0;
        int cnt = 0;
        for (int t = i - 9; t <= i - 1; t++)
        {
            if (t - 1 < 0 || s.close[t - 1] <= 0) continue;
            sumAbs += Math.abs(s.close[t] / s.close[t - 1] - 1);
            cnt++;
        }
        if (cnt == 0 || sumAbs / cnt > 0.03) return null;
        // 加速信号：突破近30日高点
        if (s.close[i] <= max(s.high, 30, i - 1)) return null;
        // 放量：量比 ≥ 1.5 倍5日均量
        double volMa5 = s.volMa5[i];
        if (Double.isNaN(volMa5) || volMa5 <= 0) return null;
        double volRatio = s.vol[i] / volMa5;
        if (volRatio < 1.5) return null;
        return volRatio * 50;
    }

    // ==================== 战法四：横盘出击 ====================

    /**
     * 大涨后长时间横盘（近20日振幅≤10%），末期放量突破。
     * 突破日阳线收盘突破横盘最高，量比≥1.5倍20日均量且≤3.8倍前一日，
     * 均线多头排列、CCI<110、KDJ J<110。
     */
    private Double eval4(StockSeries s)
    {
        if (s.n < 25) return null;
        int i = s.n - 1;
        // 横盘：近20日（不含当日）振幅≤5%
        double high20 = max(s.high, 20, i - 1);
        double low20 = min(s.low, 20, i - 1);
        if (low20 <= 0) return null;
        if ((high20 - low20) / low20 > 0.10) return null;
        // 突破日：阳线，收盘突破横盘最高
        if (s.close[i] <= s.open[i]) return null;
        if (s.close[i] <= high20) return null;
        // 放量：≥1.5倍20日均量 且 ≤3.8倍前一日
        double volMa20 = s.volMa20[i];
        if (Double.isNaN(volMa20) || volMa20 <= 0) return null;
        double ratio20 = s.vol[i] / volMa20;
        if (ratio20 < 1.5) return null;
        if (s.vol[i] > s.vol[i - 1] * 3.8) return null;
        // 均线多头排列
        if (!(s.ma5[i] > s.ma10[i] && s.ma10[i] > s.ma20[i])) return null;
        // 未过热：CCI<110、KDJ J<110
        if (!Double.isNaN(s.cci14[i]) && s.cci14[i] >= 110) return null;
        if (!Double.isNaN(s.j[i]) && s.j[i] >= 110) return null;
        return ratio20 * 50;
    }

    // ==================== 战法五：跳空缺口 ====================

    /**
     * 关键位置跳空高开（开盘>前日最高价2%）且不回补。
     * 跳空日倍量（≥前一日2倍），随后震荡3天以上缺口不回补。
     */
    private Double eval5(StockSeries s)
    {
        if (s.n < 15) return null;
        for (int g = Math.max(s.n - 10, 1); g <= s.n - 4; g++)
        {
            if (s.high[g - 1] <= 0) continue;
            double gap = s.open[g] / s.high[g - 1] - 1;
            // 2%以上有效跳空
            if (gap < 0.02) continue;
            // 跳空日倍量
            if (s.vol[g] < s.vol[g - 1] * 2) continue;
            // 震荡时间3天以上（最多回看10天）
            int end = Math.min(g + 10, s.n - 1);
            if (end - g < 3) continue;
            // 缺口不回补：后续低点均高于跳空前一日最高价
            boolean filled = false;
            for (int t = g + 1; t <= end; t++)
            {
                if (s.low[t] <= s.high[g - 1])
                {
                    filled = true;
                    break;
                }
            }
            if (filled) continue;
            return gap * 100;
        }
        return null;
    }

    // ==================== 战法六：绝地反击 ====================

    /**
     * 大幅下跌后的超跌反转。股价低于34日均线14%且创近66日新低，
     * 反击日为高位收盘的放量长阳（收盘位于振幅上端，涨幅>3%，实体涨幅>4%，量比≥1.3倍5日均量）。
     * 该战法为逆势策略，不套用「均线空头过滤」。
     */
    private Double eval6(StockSeries s)
    {
        if (s.n < 70) return null;
        int i = s.n - 1;
        // 超跌确认：低于34日均线14%
        double ma34 = s.ma34[i];
        if (Double.isNaN(ma34) || ma34 <= 0) return null;
        if (s.close[i] >= ma34 * 0.86) return null;
        // 创最近66日新低
        if (s.low[i] > min(s.low, 66, i) + EPS) return null;
        // 高位收盘（近似光头阳线）：收盘价位于当日振幅上端 80% 以上
        if (!upperClose(s, i)) return null;
        if (s.open[i] <= 0 || s.close[i - 1] <= 0) return null;
        double gain = s.close[i] / s.close[i - 1] - 1;
        if (gain <= 0.03) return null;
        double bodyGain = (s.close[i] - s.open[i]) / s.open[i];
        if (bodyGain <= 0.04) return null;
        // 放量：≥1.3倍5日均量
        double volMa5 = s.volMa5[i];
        if (Double.isNaN(volMa5) || volMa5 <= 0) return null;
        if (s.vol[i] < volMa5 * 1.3) return null;
        return gain * 100;
    }

    // ==================== 战法七：二次启动 ====================

    /**
     * 龙头股第一波涨停后回调不破涨停最低价，随后放量突破前涨停收盘开启第二波。
     * 前一波为板块涨停的光头阳线（高位收盘），整理期2~28日振幅≤15%，
     * 二次启动日成交量≥前一日3倍且放量突破第一波涨停收盘价。
     */
    private Double eval7(StockSeries s)
    {
        if (s.n < 45) return null;
        int i = s.n - 1;
        if (s.vol[i - 1] <= 0) return null;
        double volRatio = s.vol[i] / s.vol[i - 1];
        // 二次启动日：倍量阳线（成交量≥前一日3倍）
        if (volRatio < 3) return null;
        if (s.close[i] <= s.open[i]) return null;

        // 在前一波（最近40日内）寻找涨停大阳线 p
        for (int p = Math.max(s.n - 40, 1); p <= i - 2; p++)
        {
            if (s.close[p - 1] <= 0) continue;
            double gain = s.close[p] / s.close[p - 1] - 1;
            if (gain <= s.limitUp * 0.98) continue;
            // 高位收盘（近似光头阳线）
            if (!upperClose(s, p)) continue;
            // 整理期：p 后 2~28 个交易日
            int lo = p + 2;
            int hi = Math.min(p + 28, i - 1);
            if (lo > hi) continue;
            // 整理期振幅 ≤ 15%
            double hh = max(s.high, hi - lo + 1, hi);
            double ll = min(s.low, hi - lo + 1, hi);
            if (ll <= 0 || (hh - ll) / ll > 0.15) continue;
            // 回调不破涨停板最低价
            if (ll < s.low[p] - EPS) continue;
            // 二次启动日放量突破第一波涨停收盘价（非追涨）
            if (s.close[i] <= s.close[p]) continue;
            return volRatio * 30;
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

    // ==================== 数据结构 ====================

    /**
     * 单只股票的技术指标序列：OHLCV 来自K线，MA/KDJ/CCI/RSI/MACD 来自缓存，量均线由成交量现算
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
        double[] k, d, j, cci14, dif, dea;

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
        s.k = new double[n]; s.d = new double[n]; s.j = new double[n];
        s.cci14 = new double[n]; s.dif = new double[n]; s.dea = new double[n];
        Arrays.fill(s.ma5, Double.NaN); Arrays.fill(s.ma10, Double.NaN); Arrays.fill(s.ma20, Double.NaN);
        Arrays.fill(s.ma30, Double.NaN); Arrays.fill(s.ma34, Double.NaN); Arrays.fill(s.ma60, Double.NaN);
        Arrays.fill(s.k, Double.NaN); Arrays.fill(s.d, Double.NaN); Arrays.fill(s.j, Double.NaN);
        Arrays.fill(s.cci14, Double.NaN); Arrays.fill(s.dif, Double.NaN); Arrays.fill(s.dea, Double.NaN);

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
                s.k[i] = val(ind.getKdjK());
                s.d[i] = val(ind.getKdjD());
                s.j[i] = val(ind.getKdjJ());
                s.cci14[i] = val(ind.getCci());
                s.dif[i] = val(ind.getMacdDif());
                s.dea[i] = val(ind.getMacdDea());
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
