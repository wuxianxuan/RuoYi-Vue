package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDayTradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 趋势积累池减法定时任务
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法，逐个复查分组内股票，
 * 将趋势不再向上的股票移除（趋势判定复用趋势上涨选股的「逐步上涨」算法）。
 * 建议 cron: 0 50 16 ? * MON-FRI（每个交易日下午16:50，依赖趋势上涨选股 stockRisingTrendTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockDayTradingTask.execute()
 */
@Component("stockDayTradingTask")
public class StockDayTradingTask
{
    private static final Logger log = LoggerFactory.getLogger(StockDayTradingTask.class);

    @Autowired
    private IStockDayTradingService stockDayTradingService;

    /**
     * 执行趋势积累池减法
     */
    public void execute()
    {
        log.info("开始执行适合做T选股...");
        try
        {
            stockDayTradingService.execute();
        }
        catch (Exception e)
        {
            log.error("适合做T选股执行失败", e);
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
