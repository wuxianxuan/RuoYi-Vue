package com.ruoyi.system.service;

/**
 * 股票推荐引擎 Service
 */
public interface IStockRecommendService
{
    /**
     * 执行板块热度推荐引擎
     *
     * 基于涨停数据，按板块热度评分，取 Top N 板块，输出 CSV 文件
     */
    void executePlateRecommend();
}
