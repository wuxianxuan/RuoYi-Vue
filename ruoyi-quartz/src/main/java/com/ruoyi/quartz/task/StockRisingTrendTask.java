package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 趋势上涨选股定时任务
 *
 * 以指定日期（入参，为空则当天）为截止日，往前回看若干日历日K线，
 * 排除创业板/科创板与 ST/*ST，切片链上任一天不得为涨停，
 * 从收盘价最低的那天切片到最近一天，
 * 判定收盘价链逐步上涨（整体上涨、偏离次数与偏离程度受限），命中股票累加进固定分组。
 * 建议 cron: 0 40 16 ? * MON-FRI（每个交易日下午16:40，依赖 K线同步 stockKlineSyncTask 已完成）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockRisingTrendTask.execute(日期) 或 stockRisingTrendTask.execute()
 */
@Component("stockRisingTrendTask")
public class StockRisingTrendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRisingTrendTask.class);

    @Autowired
    private IStockRisingTrendService stockRisingTrendService;

    /**
     * 执行趋势上涨选股（无入参日期，默认当天）
     */
    public void execute()
    {
        log.info("开始执行趋势上涨选股...");
        try
        {
            stockRisingTrendService.execute(null);
        }
        catch (Exception e)
        {
            log.error("趋势上涨选股执行失败", e);
        }
    }

    /**
     * 带参入口：入参为截止日期（yyyy-MM-dd），为空则默认当天
     *
     * @param params 截止日期（yyyy-MM-dd）
     */
    public void execute(String params)
    {
        log.info("开始执行趋势上涨选股（截止日期：{}）...", params);
        try
        {
            stockRisingTrendService.execute(params);
        }
        catch (Exception e)
        {
            log.error("趋势上涨选股执行失败", e);
        }
    }
}
