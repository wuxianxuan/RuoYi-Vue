package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockStealthRallyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 偷偷上涨选股定时任务
 *
 * 找出最近 lookbackDays 个交易日内「偷偷上涨」的股票：累计温和上涨 + 无涨停 + 缩量 + 低换手 + 低振幅，
 * 命中股票生成一个新分组。
 * 建议 cron: 0 35 16 ? * MON-FRI（每个交易日下午16:35，依赖 K线同步 stockKlineSyncTask 与行情同步 stockQuoteSyncTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockStealthRallyTask.execute()
 */
@Component("stockStealthRallyTask")
public class StockStealthRallyTask
{
    private static final Logger log = LoggerFactory.getLogger(StockStealthRallyTask.class);

    @Autowired
    private IStockStealthRallyService stockStealthRallyService;

    /**
     * 执行偷偷上涨选股
     */
    public void execute()
    {
        log.info("开始执行偷偷上涨选股...");
        try
        {
            stockStealthRallyService.execute();
        }
        catch (Exception e)
        {
            log.error("偷偷上涨选股执行失败", e);
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
