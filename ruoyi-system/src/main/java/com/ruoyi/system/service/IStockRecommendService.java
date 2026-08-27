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
     * 涨停数降序筛出候选（涨停数 >= 阈值 取前 N），再按「强势趋势中继」打分取 Top N，生成分组。
     */
    void executeFocusPlateRecommend();
}
