package com.ruoyi.system.service;

/**
 * 股票推荐引擎 Service
 */
public interface IStockRecommendService
{
    /**
     * 执行重点关注板块入场推荐引擎
     *
     * 遍历 focus_flag=1 的板块（行业+概念），对每个板块下属股票按
     * 「强势趋势中继」打分，取 Top N 只值得入场的股票，生成分组。
     */
    void executeFocusPlateRecommend();
}
