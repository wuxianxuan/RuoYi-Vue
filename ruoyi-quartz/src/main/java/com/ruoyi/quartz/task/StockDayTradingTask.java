package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDayTradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 趋势积累池减法 + 日推荐定时任务
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法并产出当日推荐：
 * 走下坡的股票移出积累池，剩余股票经最近趋势过滤后生成当天日推荐分组。
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
     * 执行趋势积累池减法 + 日推荐（无入参日期，默认当天）
     */
    public void execute()
    {
        execute(null);
    }

    /**
     * 带参入口：入参为截止日期（yyyy-MM-dd），为空则默认当天
     *
     * @param params 截止日期（yyyy-MM-dd）
     */
    public void execute(String params)
    {
        log.info("开始执行趋势积累池减法 + 日推荐...");
        try
        {
            stockDayTradingService.execute(params);
        }
        catch (Exception e)
        {
            log.error("趋势积累池减法 + 日推荐执行失败", e);
        }
    }
}
