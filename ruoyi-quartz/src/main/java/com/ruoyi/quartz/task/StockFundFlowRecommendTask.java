package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockFundFlowRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 行业/概念资金流向推荐定时任务
 *
 * 根据 stock_fund_flow 的资金流向数据，推荐「走势向上（指数区间上涨+均线多头）且主力资金持续净流入」的行业/概念，
 * 并同步到 stock_plate（已存在更新备注+置重点关注，不存在则新建）；
 * 另将行业/概念当前涨幅前7（各7个）直接同步到 stock_plate。
 * 建议 cron: 0 30 16 ? * MON-FRI（每个交易日下午16:30，资金流向同步 stockQuoteSyncTask 完成之后）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockFundFlowRecommendTask.execute()
 */
@Component("stockFundFlowRecommendTask")
public class StockFundFlowRecommendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockFundFlowRecommendTask.class);

    @Autowired
    private IStockFundFlowRecommendService stockFundFlowRecommendService;

    /**
     * 执行资金流向板块推荐
     */
    public void execute()
    {
        log.info("开始执行行业/概念资金流向推荐...");
        try
        {
            stockFundFlowRecommendService.execute();
        }
        catch (Exception e)
        {
            log.error("行业/概念资金流向推荐执行失败", e);
        }
    }

    /**
     * 带参入口（参数预留，当前无参也走同一逻辑）
     *
     * @param params 参数（预留扩展）
     */
    public void execute(String params)
    {
        execute();
    }
}
