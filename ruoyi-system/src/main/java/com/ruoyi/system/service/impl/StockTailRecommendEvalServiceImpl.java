package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockTailRecommendRecord;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockTailRecommendRecordMapper;
import com.ruoyi.system.service.IStockTailRecommendEvalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 尾盘推荐次日表现回填实现
 *
 * 取次日（下一个交易日，自动跳过周末/节假日，因为直接取下一根日K）的开高低收，
 * 计算相对推荐价（14:45 价格）的收益、最大浮盈与最大浮亏：
 *   next_change_pct       = (次日收盘 - 推荐价) / 推荐价 × 100
 *   next_max_gain_pct     = (次日最高 - 推荐价) / 推荐价 × 100
 *   next_max_drawdown_pct = (次日最低 - 推荐价) / 推荐价 × 100
 *
 * 次日K线尚未同步的记录保持待回填状态，下次运行自动补上；
 * 超过 MAX_WAIT_DAYS 仍无次日K线（长期停牌/退市）记为 NO_DATA，不再反复扫。
 *
 * @author ruoyi
 */
@Service
public class StockTailRecommendEvalServiceImpl implements IStockTailRecommendEvalService
{
    private static final Logger log = LoggerFactory.getLogger(StockTailRecommendEvalServiceImpl.class);

    /** 单次最多处理的待回填记录数（含被挡候选，约 400 条/日） */
    private static final int PENDING_LIMIT = 10000;

    /** 统计样本数（推荐组与参选组各取最近 N 条） */
    private static final int RECENT_LIMIT = 300;

    /** 超过该天数仍取不到次日K线，标记 NO_DATA（20 天可覆盖春节/国庆长假） */
    private static final int MAX_WAIT_DAYS = 20;

    @Autowired
    private StockTailRecommendRecordMapper recommendRecordMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Override
    public void execute()
    {
        log.info("========== 尾盘推荐次日表现回填开始 ==========");
        List<StockTailRecommendRecord> pending = recommendRecordMapper.selectPendingEval(PENDING_LIMIT);
        if (pending == null || pending.isEmpty())
        {
            log.info("无待回填记录");
            logSummary();
            return;
        }

        Map<String, List<StockTailRecommendRecord>> byDate = new LinkedHashMap<>();
        for (StockTailRecommendRecord r : pending)
        {
            if (r.getTradeDate() != null && r.getStockCode() != null)
            {
                byDate.computeIfAbsent(r.getTradeDate(), d -> new ArrayList<>()).add(r);
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
        LocalDate today = LocalDate.now();
        int done = 0;
        int noData = 0;
        int waiting = 0;

        for (Map.Entry<String, List<StockTailRecommendRecord>> entry : byDate.entrySet())
        {
            LocalDate tradeDate;
            try
            {
                tradeDate = LocalDate.parse(entry.getKey());
            }
            catch (Exception e)
            {
                log.warn("推荐日 [{}] 无法解析，跳过", entry.getKey());
                continue;
            }

            List<String> codes = entry.getValue().stream()
                    .map(StockTailRecommendRecord::getStockCode)
                    .distinct()
                    .collect(Collectors.toList());
            Map<String, StockKline> nextBar = loadNextBar(codes, tradeDate);
            boolean tooOld = ChronoUnit.DAYS.between(tradeDate, today) > MAX_WAIT_DAYS;

            for (StockTailRecommendRecord r : entry.getValue())
            {
                StockKline bar = nextBar.get(r.getStockCode());
                if (bar == null)
                {
                    if (tooOld)
                    {
                        r.setEvalStatus("NO_DATA");
                        recommendRecordMapper.updateEval(r);
                        noData++;
                    }
                    else
                    {
                        waiting++;
                    }
                    continue;
                }
                double recPrice = r.getRecPrice() == null ? 0 : r.getRecPrice().doubleValue();
                if (recPrice <= 0)
                {
                    r.setEvalStatus("NO_DATA");
                    recommendRecordMapper.updateEval(r);
                    noData++;
                    continue;
                }
                double open = bar.getOpenPrice() == null ? 0 : bar.getOpenPrice().doubleValue();
                double high = bar.getHighPrice() == null ? 0 : bar.getHighPrice().doubleValue();
                double low = bar.getLowPrice() == null ? 0 : bar.getLowPrice().doubleValue();
                double close = bar.getClosePrice() == null ? 0 : bar.getClosePrice().doubleValue();

                r.setNextTradeDate(sdf.format(bar.getTradeTime()));
                r.setNextOpen(bar.getOpenPrice());
                r.setNextHigh(bar.getHighPrice());
                r.setNextLow(bar.getLowPrice());
                r.setNextClose(bar.getClosePrice());
                r.setNextChangePct(pct(close, recPrice));
                r.setNextMaxGainPct(pct(high, recPrice));
                r.setNextMaxDrawdownPct(pct(low, recPrice));
                r.setEvalStatus("DONE");
                recommendRecordMapper.updateEval(r);
                done++;
            }
        }

        log.info("回填完成：成功 {} 条，无次日K线 {} 条，暂未出次日数据 {} 条", done, noData, waiting);
        logSummary();
    }

    /**
     * 批量取每只股票推荐日之后的第一个交易日日K
     */
    private Map<String, StockKline> loadNextBar(List<String> codes, LocalDate tradeDate)
    {
        Map<String, StockKline> nextBar = new HashMap<>();
        if (codes.isEmpty())
        {
            return nextBar;
        }
        List<StockKline> bars = stockKlineMapper.selectKlineByCodesAndDays(codes, "D",
                tradeDate.plusDays(1).toString(), tradeDate.plusDays(MAX_WAIT_DAYS).toString());
        if (bars == null)
        {
            return nextBar;
        }
        bars.stream()
                .filter(k -> k.getStockCode() != null && k.getTradeTime() != null && k.getClosePrice() != null)
                .sorted(Comparator.comparing(StockKline::getTradeTime))
                .forEach(k -> nextBar.putIfAbsent(k.getStockCode(), k));
        return nextBar;
    }

    private Double pct(double price, double base)
    {
        if (base <= 0 || price <= 0)
        {
            return null;
        }
        return (price - base) / base * 100;
    }

    /**
     * 打印最近样本的滚动统计：推荐组 vs 被挡组。
     * 如果被挡组的次日表现并不比推荐组差，说明闸门挡错了方向。
     */
    private void logSummary()
    {
        List<StockTailRecommendRecord> picked = recommendRecordMapper.selectEvaluatedRecent(true, RECENT_LIMIT);
        List<StockTailRecommendRecord> rejected = recommendRecordMapper.selectEvaluatedRecent(false, RECENT_LIMIT);
        logCohort("推荐组", picked);
        logCohort("推荐组-趋势", filterSource(picked, "TREND"));
        logCohort("推荐组-涨停", filterSource(picked, "LIMIT_UP"));
        logCohort("推荐组-解禁", filterSource(picked, "LOCKUP_GUIDED"));
        logCohort("被挡组", rejected);
    }

    private List<StockTailRecommendRecord> filterSource(List<StockTailRecommendRecord> rows, String source)
    {
        if (rows == null)
        {
            return new ArrayList<>();
        }
        return rows.stream()
                .filter(r -> source.equals(r.getSource()))
                .collect(Collectors.toList());
    }

    private void logCohort(String label, List<StockTailRecommendRecord> rows)
    {
        if (rows == null || rows.isEmpty())
        {
            log.info("{}：暂无已回填样本", label);
            return;
        }
        List<Double> changes = rows.stream()
                .map(StockTailRecommendRecord::getNextChangePct)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (changes.isEmpty())
        {
            log.info("{}：暂无有效样本", label);
            return;
        }
        long up = changes.stream().filter(v -> v > 0).count();
        double avgChange = changes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double avgGain = rows.stream().map(StockTailRecommendRecord::getNextMaxGainPct)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        double avgDrawdown = rows.stream().map(StockTailRecommendRecord::getNextMaxDrawdownPct)
                .filter(Objects::nonNull).mapToDouble(Double::doubleValue).average().orElse(0);
        log.info("{}：{} 条样本，次日平均涨幅 {}%，上涨占比 {}%，平均最大浮盈 {}%，平均最大浮亏 {}%",
                label, changes.size(), String.format("%.2f", avgChange),
                String.format("%.1f", up * 100.0 / changes.size()),
                String.format("%.2f", avgGain), String.format("%.2f", avgDrawdown));
    }
}
