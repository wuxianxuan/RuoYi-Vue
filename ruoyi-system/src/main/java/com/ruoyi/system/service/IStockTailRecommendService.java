package com.ruoyi.system.service;

/**
 * 尾盘推荐 Service
 *
 * 每个交易日下午 14:45 运行（收盘前），基于「昨日分组（上升/震荡/涨停首二板）」的股票，
 * 拉取当日分时（分钟）K线，按「尾盘可买」口径筛选：
 * 未涨停、当日上涨、收盘贴近阶段高点、尾盘不跳水、量比达标，生成「尾盘推荐_日期」分组。
 */
public interface IStockTailRecommendService
{
    /**
     * 执行尾盘推荐
     *
     * @param date 截止日期（yyyy-MM-dd），为空则默认当天
     */
    void execute(String date);
}
