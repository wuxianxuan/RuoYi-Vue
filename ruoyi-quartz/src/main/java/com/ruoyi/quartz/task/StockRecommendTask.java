package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 股票推荐定时任务
 *
 * 遍历 focus_flag=1 的重点关注板块（行业+概念），对每个板块下属股票按
 * 涨停数降序筛出候选（涨停数 >= 阈值 取前 N），再按「强势趋势中继」打分取 Top N 生成分组。
 * 建议执行时间：每日16:00（收盘后），依赖 K线同步(stockKlineSyncTask)与
 * 指标计算(stockIndicatorCalcTask)已完成。
 */
@Component("stockRecommendTask")
public class StockRecommendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendTask.class);

    @Autowired
    private IStockRecommendService stockRecommendService;

    /**
     * 执行重点关注板块入场推荐，生成分组
     */
    public void recommend()
    {
        log.info("开始执行重点关注板块入场推荐...");
        try
        {
            stockRecommendService.executeFocusPlateRecommend();
        }
        catch (Exception e)
        {
            log.error("重点关注板块入场推荐执行失败", e);
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
