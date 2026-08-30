package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockLeaderStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 龙头股七大战法推荐定时任务
 *
 * 遍历 focus_flag=1 的重点关注板块（行业+概念），汇总成分股后，按
 * 「龙头股必杀七大战法」逐一识别，每个战法生成一个分组。
 * 建议执行时间：每日16:00（收盘后），依赖 K线同步(stockKlineSyncTask)已完成。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockLeaderStrategyTask.execute()
 */
@Component("stockLeaderStrategyTask")
public class StockLeaderStrategyTask
{
    private static final Logger log = LoggerFactory.getLogger(StockLeaderStrategyTask.class);

    @Autowired
    private IStockLeaderStrategyService stockLeaderStrategyService;

    /**
     * 执行龙头股七大战法选股
     */
    public void execute()
    {
        log.info("开始执行龙头股七大战法选股...");
        try
        {
            stockLeaderStrategyService.execute();
        }
        catch (Exception e)
        {
            log.error("龙头股七大战法选股执行失败", e);
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
