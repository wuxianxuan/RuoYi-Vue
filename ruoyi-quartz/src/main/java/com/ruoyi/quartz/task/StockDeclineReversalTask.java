package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDeclineReversalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 下跌反转选股定时任务
 *
 * 查询最近 a 日K线，判断最近一天是否为 a 天内最低的收盘价，
 * 若是则继续判断最近一天是否为「缩量下跌」（收盘价较前一日下跌、成交量较前一日缩量），命中股票生成一个新分组。
 * 建议 cron: 0 50 16 ? * MON-FRI（每个交易日下午16:50，依赖 K线同步 stockKlineSyncTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockDeclineReversalTask.execute()
 */
@Component("stockDeclineReversalTask")
public class StockDeclineReversalTask
{
    private static final Logger log = LoggerFactory.getLogger(StockDeclineReversalTask.class);

    @Autowired
    private IStockDeclineReversalService stockDeclineReversalService;

    /**
     * 执行下跌反转选股
     */
    public void execute()
    {
        log.info("开始执行下跌反转选股...");
        try
        {
            stockDeclineReversalService.execute();
        }
        catch (Exception e)
        {
            log.error("下跌反转选股执行失败", e);
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
