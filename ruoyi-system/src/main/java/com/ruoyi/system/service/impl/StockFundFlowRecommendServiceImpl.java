package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockFundFlow;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockFundFlowMapper;
import com.ruoyi.system.mapper.StockPlateMapper;
import com.ruoyi.system.service.IStockFundFlowRecommendService;
import com.ruoyi.system.service.IStockPlateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 行业/概念资金流向推荐引擎实现
 *
 * 数据来源：stock_fund_flow（行业/概念资金流向快照）。
 * 推荐信号（双重硬门槛）：
 *   1. 资金面：近 lookbackDays 个交易日内连续 minConsecutiveDays 日净流入(je>0)，
 *      且累计净流入达到 minNetInflow 亿，最新交易日仍为净流入；
 *   2. 趋势面：近 trendWindow 个交易日内指数区间涨幅 > minTrendPct，且 MA5(price) > MA10(price) 均线多头。
 * 排序评分：区间涨幅(趋势面)与累计净流入(资金面)在候选集内 min-max 归一化后加权求和，
 * 按综合分降序取前 topN 个板块。
 *
 * 另附涨幅TOP逻辑：行业/概念各取当前涨幅(trade_zdf)前 topZdfN 个直接同步（不走资金/趋势门槛）。
 *
 * 输出：按 (plate_type, trade_name) 匹配 stock_plate，
 *   - 已存在：更新备注为「yyyy-MM-dd推荐行业/概念」，focus_flag=1
 *   - 不存在：新建板块，备注为「yyyy-MM-dd推荐行业/概念+url」
 *
 * @author ruoyi
 */
@Service
public class StockFundFlowRecommendServiceImpl implements IStockFundFlowRecommendService
{
    private static final Logger log = LoggerFactory.getLogger(StockFundFlowRecommendServiceImpl.class);

    @Autowired
    private StockFundFlowMapper stockFundFlowMapper;

    @Autowired
    private StockPlateMapper stockPlateMapper;

    @Autowired
    private IStockPlateService stockPlateService;

    /** 回看交易日数 */
    @Value("${stock.fund-flow-recommend.lookback-days:5}")
    private int lookbackDays;

    /** 最少连续净流入天数 */
    @Value("${stock.fund-flow-recommend.min-consecutive-days:3}")
    private int minConsecutiveDays;

    /** 累计净流入阈值（亿） */
    @Value("${stock.fund-flow-recommend.min-net-inflow:1.0}")
    private BigDecimal minNetInflow;

    /** 最少公司家数（0 表示不限制） */
    @Value("${stock.fund-flow-recommend.min-total:0}")
    private int minTotal;

    /** 每个类型最多推荐数量 */
    @Value("${stock.fund-flow-recommend.top-n:10}")
    private int topN;

    /** 趋势回看交易日数（计算区间涨幅与 MA5/MA10） */
    @Value("${stock.fund-flow-recommend.trend-window:15}")
    private int trendWindow;

    /** 区间涨幅阈值(%)，仅当区间涨幅 > 该值才判定走势向上 */
    @Value("${stock.fund-flow-recommend.min-trend-pct:0}")
    private BigDecimal minTrendPct;

    /** 趋势面（区间涨幅）权重 */
    @Value("${stock.fund-flow-recommend.weight-trend:0.5}")
    private BigDecimal weightTrend;

    /** 资金面（累计净流入）权重 */
    @Value("${stock.fund-flow-recommend.weight-fund:0.5}")
    private BigDecimal weightFund;

    /** 涨幅TOP同步数量（每个类型取当前涨幅前 N 个，行业+概念共 2N 个） */
    @Value("${stock.fund-flow-recommend.top-zdf-n:7}")
    private int topZdfN;

    @Override
    public void execute()
    {
        log.info("========== 资金流向推荐引擎开始 ==========");
        int industryCount = recommend("INDUSTRY");
        int conceptCount = recommend("CONCEPT");
        int zdfIndustryCount = recommendTopZdf("INDUSTRY");
        int zdfConceptCount = recommendTopZdf("CONCEPT");
        log.info("========== 资金流向推荐完成：行业 {} 个，概念 {} 个；涨幅TOP行业 {} 个，概念 {} 个 ==========",
                industryCount, conceptCount, zdfIndustryCount, zdfConceptCount);
    }

    // ==================== 推荐逻辑 ====================

    /**
     * 对指定板块类型执行推荐
     *
     * @param plateType INDUSTRY / CONCEPT
     * @return 推荐（落库）的板块数量
     */
    private int recommend(String plateType)
    {
        String typeLabel = "INDUSTRY".equals(plateType) ? "行业" : "概念";
        String dateStr = LocalDate.now().toString();

        // 1. 取最近 max(lookbackDays, trendWindow) 个交易日，覆盖资金面与趋势面
        int window = Math.max(lookbackDays, trendWindow);
        List<Date> dates = stockFundFlowMapper.selectRecentTradeDates(plateType, window);
        if (dates == null || dates.isEmpty())
        {
            log.warn("[{}] 无资金流向历史数据，跳过推荐", plateType);
            return 0;
        }

        // 2. 取这些交易日内的资金流向
        List<StockFundFlow> flows = stockFundFlowMapper.selectByPlateTypeAndDates(plateType, dates);
        if (flows == null || flows.isEmpty())
        {
            log.warn("[{}] 回看交易日内无资金流向数据，跳过推荐", plateType);
            return 0;
        }
        log.info("[{}] 回看 {} 个交易日，共 {} 条资金流向记录", plateType, dates.size(), flows.size());

        // 3. 按板块名称分组
        Map<String, List<StockFundFlow>> byName = new LinkedHashMap<>();
        for (StockFundFlow f : flows)
        {
            if (f.getTradeName() == null)
            {
                continue;
            }
            byName.computeIfAbsent(f.getTradeName(), k -> new ArrayList<>()).add(f);
        }

        // 4. 第一阶段：硬门槛过滤，收集候选原始指标
        List<Candidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<StockFundFlow>> entry : byName.entrySet())
        {
            List<StockFundFlow> list = entry.getValue();
            list.sort(Comparator.comparing(StockFundFlow::getTradeDate));
            Candidate c = buildCandidate(entry.getKey(), list);
            if (c != null)
            {
                candidates.add(c);
            }
        }

        if (candidates.isEmpty())
        {
            log.info("[{}] 无满足资金净流入且走势向上条件的板块", plateType);
            return 0;
        }

        // 5. 第二阶段：min-max 归一化 + 加权评分，按综合分降序取 Top N
        scoreCandidates(candidates);
        candidates.sort(Comparator.comparing(Candidate::getScore).reversed());
        int topN = Math.min(this.topN, candidates.size());
        List<Candidate> top = candidates.subList(0, topN);

        // 6. 落库到 stock_plate
        int saved = 0;
        for (Candidate c : top)
        {
            try
            {
                upsertPlate(plateType, c.name, c.url, dateStr + "推荐" + typeLabel);
                saved++;
                log.info("   推荐[{}] {}：区间涨幅 {}%，连续 {} 日净流入，累计净额 {} 亿，综合分 {}",
                        plateType, c.name, c.trendPct, c.consecutive, c.netSum, c.score);
            }
            catch (Exception e)
            {
                log.error("   推荐[{}] {} 落库失败: {}", plateType, c.name, e.getMessage(), e);
            }
        }
        return saved;
    }

    /**
     * 将指定板块类型当前涨幅（trade_zdf）前 topZdfN 的板块直接同步到 stock_plate（不经过资金/趋势门槛）
     *
     * @param plateType INDUSTRY / CONCEPT
     * @return 同步（落库）的板块数量
     */
    private int recommendTopZdf(String plateType)
    {
        String typeLabel = "INDUSTRY".equals(plateType) ? "行业" : "概念";
        String dateStr = LocalDate.now().toString();

        // 1. 取最新一个交易日
        List<Date> dates = stockFundFlowMapper.selectRecentTradeDates(plateType, 1);
        if (dates == null || dates.isEmpty())
        {
            log.warn("[{}] 无资金流向数据，跳过涨幅TOP同步", plateType);
            return 0;
        }

        // 2. 取最新交易日数据
        List<StockFundFlow> flows = stockFundFlowMapper.selectByPlateTypeAndDates(plateType, dates);
        if (flows == null || flows.isEmpty())
        {
            log.warn("[{}] 最新交易日无数据，跳过涨幅TOP同步", plateType);
            return 0;
        }

        // 3. 按涨跌幅降序取前 topZdfN
        List<StockFundFlow> sorted = new ArrayList<>(flows);
        sorted.sort((a, b) -> {
            BigDecimal za = a.getTradeZdf();
            BigDecimal zb = b.getTradeZdf();
            if (za == null && zb == null) return 0;
            if (za == null) return 1;
            if (zb == null) return -1;
            return zb.compareTo(za);
        });
        int n = Math.min(topZdfN, sorted.size());
        List<StockFundFlow> top = sorted.subList(0, n);

        // 4. 落库
        int saved = 0;
        for (StockFundFlow f : top)
        {
            try
            {
                upsertPlate(plateType, f.getTradeName(), f.getUrl(), dateStr + "涨幅TOP" + typeLabel);
                saved++;
                log.info("   涨幅TOP[{}] {}：涨跌幅 {}%", plateType, f.getTradeName(), f.getTradeZdf());
            }
            catch (Exception e)
            {
                log.error("   涨幅TOP[{}] {} 落库失败: {}", plateType, f.getTradeName(), e.getMessage(), e);
            }
        }
        return saved;
    }

    /**
     * 单板块双重硬门槛校验（资金面 + 趋势面）+ 收集原始指标，通过则返回候选，否则返回 null
     */
    private Candidate buildCandidate(String name, List<StockFundFlow> list)
    {
        int n = list.size();
        StockFundFlow latest = list.get(n - 1);

        // 公司家数过滤（minTotal=0 表示不限制）
        Integer total = latest.getTotal();
        if (minTotal > 0 && (total == null || total < minTotal))
        {
            return null;
        }

        // ========== 资金面：最近 lookbackDays 个交易日 ==========
        int fundStart = Math.max(0, n - lookbackDays);
        List<StockFundFlow> fundWindow = list.subList(fundStart, n);

        // 连续净流入天数（从最新交易日往前，仅在资金窗口内统计）
        int consecutive = 0;
        for (int i = fundWindow.size() - 1; i >= 0; i--)
        {
            BigDecimal je = fundWindow.get(i).getJe();
            if (je != null && je.compareTo(BigDecimal.ZERO) > 0)
            {
                consecutive++;
            }
            else
            {
                break;
            }
        }

        // 资金窗口内累计净流入
        BigDecimal netSum = BigDecimal.ZERO;
        for (StockFundFlow f : fundWindow)
        {
            if (f.getJe() != null)
            {
                netSum = netSum.add(f.getJe());
            }
        }

        BigDecimal latestJe = latest.getJe();
        if (consecutive < minConsecutiveDays)
        {
            return null;
        }
        if (netSum.compareTo(minNetInflow) < 0)
        {
            return null;
        }
        if (latestJe == null || latestJe.compareTo(BigDecimal.ZERO) <= 0)
        {
            return null;
        }

        // ========== 趋势面：最近 trendWindow 个交易日 ==========
        int trendStart = Math.max(0, n - trendWindow);
        List<StockFundFlow> trendWindowList = list.subList(trendStart, n);

        // 区间涨幅：(最新指数 - 区间首日指数) / 区间首日指数
        BigDecimal firstPrice = trendWindowList.get(0).getPrice();
        BigDecimal latestPrice = latest.getPrice();
        BigDecimal trendPct = null;
        if (firstPrice != null && latestPrice != null && firstPrice.compareTo(BigDecimal.ZERO) != 0)
        {
            trendPct = latestPrice.subtract(firstPrice)
                    .divide(firstPrice, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
        }

        // 均线多头：MA5(price) > MA10(price)
        BigDecimal ma5 = maPrice(trendWindowList, 5);
        BigDecimal ma10 = maPrice(trendWindowList, 10);

        boolean trendUp = trendPct != null
                && trendPct.compareTo(minTrendPct) > 0
                && ma5 != null && ma10 != null
                && ma5.compareTo(ma10) > 0;

        if (!trendUp)
        {
            return null;
        }

        Candidate c = new Candidate();
        c.name = name;
        c.url = latest.getUrl();
        c.trendPct = trendPct;
        c.consecutive = consecutive;
        c.netSum = netSum;
        return c;
    }

    /**
     * 计算 price 的 period 日均线（用最近 period 个交易日），数据不足或含空值返回 null
     */
    private BigDecimal maPrice(List<StockFundFlow> list, int period)
    {
        int n = list.size();
        if (n < period)
        {
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = n - period; i < n; i++)
        {
            BigDecimal p = list.get(i).getPrice();
            if (p == null)
            {
                return null;
            }
            sum = sum.add(p);
        }
        return sum.divide(BigDecimal.valueOf(period), 6, RoundingMode.HALF_UP);
    }

    /**
     * 按 (类型, 名称) 匹配 stock_plate 并更新/插入推荐记录
     *
     * @param remark 备注（不含 url，新建板块时会在末尾追加 url）
     */
    private void upsertPlate(String plateType, String plateName, String url, String remark)
    {
        StockPlate existing = stockPlateMapper.selectPlateByNameAndType(plateName, plateType);
        if (existing != null)
        {
            // 已存在：更新备注 + 置为重点关注
            StockPlate update = new StockPlate();
            update.setId(existing.getId());
            update.setRemark(remark);
            update.setFocusFlag(1);
            update.setUpdateBy("system");
            stockPlateService.updatePlate(update);
            log.debug("更新板块 [{}] {}：remark={}, focus_flag=1", plateType, plateName, update.getRemark());
        }
        else
        {
            // 不存在：新建板块，备注带同花顺详情页 url
            StockPlate plate = new StockPlate();
            plate.setPlateName(plateName);
            plate.setPlateType(plateType);
            plate.setRemark(remark + (url == null ? "" : "+" + url));
            plate.setFocusFlag(1);
            plate.setCreateBy("system");
            stockPlateService.insertPlate(plate);
            log.debug("新增板块 [{}] {}：remark={}, focus_flag=1", plateType, plateName, plate.getRemark());
        }
    }

    // ==================== 内部数据结构 ====================

    private static class Candidate
    {
        String name;
        String url;
        BigDecimal trendPct;  // 区间涨幅（趋势面）
        int consecutive;      // 连续净流入天数（资金面）
        BigDecimal netSum;    // 累计净流入（资金面）
        BigDecimal score;     // 综合分

        BigDecimal getScore()
        {
            return score;
        }
    }

    /**
     * 对候选集做 min-max 归一化后加权求和（趋势面区间涨幅 + 资金面累计净流入）
     */
    private void scoreCandidates(List<Candidate> candidates)
    {
        BigDecimal trendMin = minOf(candidates, c -> c.trendPct);
        BigDecimal trendMax = maxOf(candidates, c -> c.trendPct);
        BigDecimal fundMin = minOf(candidates, c -> c.netSum);
        BigDecimal fundMax = maxOf(candidates, c -> c.netSum);

        for (Candidate c : candidates)
        {
            BigDecimal nTrend = normalize(c.trendPct, trendMin, trendMax);
            BigDecimal nFund = normalize(c.netSum, fundMin, fundMax);
            c.score = weightTrend.multiply(nTrend).add(weightFund.multiply(nFund));
        }
    }

    /** min-max 归一化到 [0,1]，max==min 时取 0.5 避免除零 */
    private BigDecimal normalize(BigDecimal value, BigDecimal min, BigDecimal max)
    {
        if (value == null)
        {
            return BigDecimal.ZERO;
        }
        if (max.compareTo(min) == 0)
        {
            return new BigDecimal("0.5");
        }
        return value.subtract(min).divide(max.subtract(min), 6, RoundingMode.HALF_UP);
    }

    private BigDecimal minOf(List<Candidate> candidates, Function<Candidate, BigDecimal> getter)
    {
        BigDecimal min = null;
        for (Candidate c : candidates)
        {
            BigDecimal v = getter.apply(c);
            if (v == null)
            {
                continue;
            }
            if (min == null || v.compareTo(min) < 0)
            {
                min = v;
            }
        }
        return min == null ? BigDecimal.ZERO : min;
    }

    private BigDecimal maxOf(List<Candidate> candidates, Function<Candidate, BigDecimal> getter)
    {
        BigDecimal max = null;
        for (Candidate c : candidates)
        {
            BigDecimal v = getter.apply(c);
            if (v == null)
            {
                continue;
            }
            if (max == null || v.compareTo(max) > 0)
            {
                max = v;
            }
        }
        return max == null ? BigDecimal.ZERO : max;
    }
}
