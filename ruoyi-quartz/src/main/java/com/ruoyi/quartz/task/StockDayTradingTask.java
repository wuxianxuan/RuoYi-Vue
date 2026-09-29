package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDayTradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 适合做T选股定时任务
 *
 * 取最近 lookbackDays 个交易日的每日行情快照（stock_daily_quote），
 * 对每只股票计算近N日的 振幅/换手率/成交额/量比 平均值，四类指标均达到阈值即命中，
 * 命中股票生成一个新分组。
 * 建议 cron: 0 50 16 ? * MON-FRI（每个交易日下午16:50，依赖行情同步 stockQuoteSyncTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockDayTradingTask.execute()
 */
@Component("stockDayTradingTask")
public class StockDayTradingTask
{
    private static final Logger log = LoggerFactory.getLogger(StockDayTradingTask.class);

    @Autowired
    private IStockDayTradingService stockDayTradingService;

    /**
     * 执行适合做T选股
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
