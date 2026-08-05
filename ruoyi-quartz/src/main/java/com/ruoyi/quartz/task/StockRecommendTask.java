package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 股票推荐定时任务
 *
 * 基于板块涨停热度分析，推荐热门板块及板块内强势股票，结果输出为CSV文件。
 * 建议执行时间：每日16:00（收盘后），依赖涨停采集任务(stockLimitUpCollectTask)已完成。
 */
@Component("stockRecommendTask")
public class StockRecommendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendTask.class);

    @Autowired
    private IStockRecommendService stockRecommendService;

    /**
     * 执行板块热度推荐，输出CSV文件
     */
    public void recommend()
    {
        log.info("开始执行板块热度推荐...");
        try
        {
            stockRecommendService.executePlateRecommend();
        }
        catch (Exception e)
        {
            log.error("板块热度推荐执行失败", e);
        }
    }

    /**
     * 带参入口（参数预留，当前无参也走板块推荐逻辑）
     *
     * @param params 参数（预留扩展）
     */
    public void recommend(String params)
    {
        recommend();
    }
}
