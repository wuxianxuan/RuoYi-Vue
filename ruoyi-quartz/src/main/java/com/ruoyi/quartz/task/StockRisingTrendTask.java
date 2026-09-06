package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 趋势上涨选股定时任务
 *
 * 查询最近 a 日K线，从收盘价最低的那天到最近一天，每 b 个点整合成一个节点，
 * 检查节点链是否逐步上涨（每 x 个节点中少于 y 个节点偏离上涨趋势），命中股票生成一个新分组。
 * 建议 cron: 0 40 16 ? * MON-FRI（每个交易日下午16:40，依赖 K线同步 stockKlineSyncTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockRisingTrendTask.execute()
 */
@Component("stockRisingTrendTask")
public class StockRisingTrendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRisingTrendTask.class);

    @Autowired
    private IStockRisingTrendService stockRisingTrendService;

    /**
     * 执行趋势上涨选股
     */
    public void execute()
    {
        log.info("开始执行趋势上涨选股...");
        try
        {
            stockRisingTrendService.execute();
        }
        catch (Exception e)
        {
            log.error("趋势上涨选股执行失败", e);
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
