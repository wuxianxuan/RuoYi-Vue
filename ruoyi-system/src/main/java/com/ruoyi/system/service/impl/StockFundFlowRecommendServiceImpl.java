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
 * 推荐信号（硬门槛）：近 lookbackDays 个交易日内连续 minConsecutiveDays 日净流入(je>0)，
 * 且累计净流入达到 minNetInflow 亿，最新交易日仍为净流入。
 * 排序评分：最新交易日的涨跌幅(tradeZdf)、流入资金(buy)、流出资金(sell)、净额(je)
 * 四个指标在候选集内 min-max 归一化后加权求和，按综合分降序取前 topN 个板块。
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

    /** 涨跌幅权重 */
    @Value("${stock.fund-flow-recommend.weight-zdf:0.3}")
    private BigDecimal weightZdf;

    /** 流入资金权重 */
    @Value("${stock.fund-flow-recommend.weight-buy:0.2}")
    private BigDecimal weightBuy;

    /** 净额权重 */
    @Value("${stock.fund-flow-recommend.weight-je:0.3}")
    private BigDecimal weightJe;

    /** 流出资金权重（负向） */
    @Value("${stock.fund-flow-recommend.weight-sell:0.2}")
    private BigDecimal weightSell;

    @Override
    public void execute()
    {
        log.info("========== 资金流向推荐引擎开始 ==========");
        int industryCount = recommend("INDUSTRY");
        int conceptCount = recommend("CONCEPT");
        log.info("========== 资金流向推荐完成：行业 {} 个，概念 {} 个 ==========", industryCount, conceptCount);
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

        // 1. 取最近 lookbackDays 个交易日
        List<Date> dates = stockFundFlowMapper.selectRecentTradeDates(plateType, lookbackDays);
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
            log.info("[{}] 无满足资金流入条件的板块", plateType);
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
                upsertPlate(plateType, c.name, c.url, typeLabel, dateStr);
                saved++;
                log.info("   推荐[{}] {}：涨跌幅 {}%，流入 {} 亿，流出 {} 亿，净额 {} 亿，连续 {} 日净流入，累计净额 {} 亿，综合分 {}",
                        plateType, c.name, c.zdf, c.buy, c.sell, c.je, c.consecutive, c.netSum, c.score);
            }
            catch (Exception e)
            {
                log.error("   推荐[{}] {} 落库失败: {}", plateType, c.name, e.getMessage(), e);
            }
        }
        return saved;
    }

    /**
     * 单板块硬门槛校验 + 收集原始指标，通过则返回候选，否则返回 null
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

        // 连续净流入天数（从最新交易日往前）
        int consecutive = 0;
        for (int i = n - 1; i >= 0; i--)
        {
            BigDecimal je = list.get(i).getJe();
            if (je != null && je.compareTo(BigDecimal.ZERO) > 0)
            {
                consecutive++;
            }
            else
            {
                break;
            }
        }

        // 近 lookbackDays 日累计净流入
        BigDecimal netSum = BigDecimal.ZERO;
        for (StockFundFlow f : list)
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

        Candidate c = new Candidate();
        c.name = name;
        c.url = latest.getUrl();
        c.zdf = latest.getTradeZdf();
        c.buy = latest.getBuy();
        c.sell = latest.getSell();
        c.je = latestJe;
        c.consecutive = consecutive;
        c.netSum = netSum;
        return c;
    }

    /**
     * 按 (类型, 名称) 匹配 stock_plate 并更新/插入推荐记录
     */
    private void upsertPlate(String plateType, String plateName, String url, String typeLabel, String dateStr)
    {
        StockPlate existing = stockPlateMapper.selectPlateByNameAndType(plateName, plateType);
        if (existing != null)
        {
            // 已存在：更新备注 + 置为重点关注
            StockPlate update = new StockPlate();
            update.setId(existing.getId());
            update.setRemark(dateStr + "推荐" + typeLabel);
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
            plate.setRemark(dateStr + "推荐" + typeLabel + "+" + (url == null ? "" : url));
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
        BigDecimal zdf;   // 涨跌幅（最新交易日）
        BigDecimal buy;   // 流入资金（最新交易日）
        BigDecimal sell;  // 流出资金（最新交易日）
        BigDecimal je;    // 净额（最新交易日）
        int consecutive;  // 连续净流入天数
        BigDecimal netSum; // 累计净流入
        BigDecimal score;  // 综合分

        BigDecimal getScore()
        {
            return score;
        }
    }

    /**
     * 对候选集做 min-max 归一化后加权求和，得到综合分
     */
    private void scoreCandidates(List<Candidate> candidates)
    {
        BigDecimal zdfMin = minOf(candidates, c -> c.zdf);
        BigDecimal zdfMax = maxOf(candidates, c -> c.zdf);
        BigDecimal buyMin = minOf(candidates, c -> c.buy);
        BigDecimal buyMax = maxOf(candidates, c -> c.buy);
        BigDecimal sellMin = minOf(candidates, c -> c.sell);
        BigDecimal sellMax = maxOf(candidates, c -> c.sell);
        BigDecimal jeMin = minOf(candidates, c -> c.je);
        BigDecimal jeMax = maxOf(candidates, c -> c.je);

        for (Candidate c : candidates)
        {
            BigDecimal nZdf = normalize(c.zdf, zdfMin, zdfMax);
            BigDecimal nBuy = normalize(c.buy, buyMin, buyMax);
            BigDecimal nSell = normalize(c.sell, sellMin, sellMax);
            BigDecimal nJe = normalize(c.je, jeMin, jeMax);

            c.score = weightZdf.multiply(nZdf)
                    .add(weightBuy.multiply(nBuy))
                    .add(weightJe.multiply(nJe))
                    .subtract(weightSell.multiply(nSell));
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
