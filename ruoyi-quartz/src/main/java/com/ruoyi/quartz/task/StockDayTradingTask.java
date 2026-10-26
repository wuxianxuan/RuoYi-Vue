package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDayTradingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 趋势积累池日推荐定时任务
 *
 * 「趋势上涨选股」任务每日全量覆盖积累分组（含 ATR 波段删除）并把波段状态写入 stock_rising_state 表；
 * 本任务按波段状态把积累池拆成「上升」「震荡」两个日推荐分组（换手率达标才进），
 * 并遍历当日涨停记录过滤出首板/二板生成「涨停首二板_日期」分组。
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
