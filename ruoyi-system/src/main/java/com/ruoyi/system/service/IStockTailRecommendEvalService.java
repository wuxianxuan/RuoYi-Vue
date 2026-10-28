package com.ruoyi.system.service;

/**
 * 尾盘推荐次日表现回填 Service
 *
 * 把 stock_tail_recommend_record 里待回填的推荐记录，用次日日K回填
 * 次日开盘/最高/最低/收盘以及相对推荐价的涨跌幅，供验证推荐口径是否降低次日回调。
 */
public interface IStockTailRecommendEvalService
{
    /**
     * 回填所有待回填记录，并打印最近样本的滚动统计
     */
    void execute();
}
