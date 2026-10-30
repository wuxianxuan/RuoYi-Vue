package com.ruoyi.quartz.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 股票数据流水线合并任务
 *
 * 把原来需要分别配置、按顺序点击的两对任务各自合并成一个入口：
 *
 *   1. {@link #syncQuoteAndFundFlow()}：同花顺行情同步 + 行业/概念资金流向推荐
 *      顺序不可颠倒——stockQuoteSyncTask 除了写 stock_daily_quote / stock，
 *      还会把 stock_fund_flow 一起落库，资金流向推荐读的就是这张表。
 *
 *   2. {@link #runRisingAndDayTrading()}：趋势上涨选股 + 趋势积累池日推荐
 *      顺序不可颠倒——日推荐读的是选股任务全量覆盖的「趋势上涨选股」分组和 stock_rising_state。
 *
 * 失败策略：
 *   - 第一步失败时不再执行第二步（依赖数据不完整），并把异常上抛让 RuoYi 任务日志标红；
 *   - 行情同步是否成功由 stockQuoteSyncTask.isLastSyncOk() 判断（文件缺失/无数据/解析异常都会置 false）。
 *
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串：
 *   stockDailyPipelineTask.syncQuoteAndFundFlow()
 *   stockDailyPipelineTask.runRisingAndDayTrading()
 *
 * @author ruoyi
 */
@Component("stockDailyPipelineTask")
public class StockDailyPipelineTask
{
    private static final Logger log = LoggerFactory.getLogger(StockDailyPipelineTask.class);

    @Autowired
    private StockQuoteSyncTask stockQuoteSyncTask;

    @Autowired
    private StockFundFlowRecommendTask stockFundFlowRecommendTask;

    @Autowired
    private StockRisingTrendTask stockRisingTrendTask;

    @Autowired
    private StockDayTradingTask stockDayTradingTask;

    /**
     * 行情同步 + 资金流向推荐（原 stockQuoteSyncTask.sync() + stockFundFlowRecommendTask.execute()）
     */
    public void syncQuoteAndFundFlow()
    {
        long start = System.currentTimeMillis();
        log.info("========== [合并任务] 行情同步 + 资金流向推荐 开始 ==========");

        log.info("---------- 步骤 1/2：同花顺行情 + 行业/概念资金流向同步 ----------");
        stockQuoteSyncTask.sync();
        if (!stockQuoteSyncTask.isLastSyncOk())
        {
            // 行情没同步成功时 stock_fund_flow 也没有新数据，继续推荐只会拿旧数据出结果
            String msg = "行情/资金流向同步失败，已跳过资金流向推荐";
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("---------- 步骤 2/2：行业/概念资金流向推荐 ----------");
        stockFundFlowRecommendTask.execute();

        log.info("========== [合并任务] 行情同步 + 资金流向推荐 完成，耗时 {} ms ==========",
                System.currentTimeMillis() - start);
    }

    /**
     * 趋势上涨选股 + 趋势积累池日推荐（原 stockRisingTrendTask.execute() + stockDayTradingTask.execute()）
     */
    public void runRisingAndDayTrading()
    {
        long start = System.currentTimeMillis();
        log.info("========== [合并任务] 趋势上涨选股 + 日推荐 开始 ==========");

        log.info("---------- 步骤 1/2：趋势上涨选股（全量重算积累池 + 波段状态） ----------");
        // 失败会直接抛出，不再执行日推荐，避免用旧的池子/状态产出日推荐分组
        stockRisingTrendTask.execute();

        log.info("---------- 步骤 2/2：趋势积累池日推荐（上升/震荡拆分 + 涨停首二板） ----------");
        stockDayTradingTask.execute();

        log.info("========== [合并任务] 趋势上涨选股 + 日推荐 完成，耗时 {} ms ==========",
                System.currentTimeMillis() - start);
    }
}
