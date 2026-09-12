package com.ruoyi.system.service;

/**
 * 趋势上涨选股 Service
 *
 * 算法：以指定日期（为空则当天）为截止日，往前回看 a 个日历日的日K线，
 * 从「收盘价最低」的那天切片到最近一天，按每 b 个点取收盘价均值整合成一个节点，
 * 检查节点链是否逐步上涨（允许偶尔偏差）：
 * 任意连续 x 个节点的窗口内，节点相对前一节点下跌的次数必须少于 y。
 * 命中股票生成一个新分组。
 */
public interface IStockRisingTrendService
{
    /**
     * 执行趋势上涨选股，命中股票生成一个分组
     *
     * @param date 截止日期（yyyy-MM-dd），为空则使用当天
     */
    void execute(String date);
}
