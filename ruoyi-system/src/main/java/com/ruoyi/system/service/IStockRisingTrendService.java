package com.ruoyi.system.service;

/**
 * 趋势上涨选股 Service
 *
 * 算法：以指定日期（为空则当天）为截止日，往前回看 lookbackDays 个日历日的日K线，
 * 排除创业板/科创板（无交易权限）与 ST/*ST，
 * 从「收盘价最低」的那天切片到最近一天（最低日到最新日合计需至少 minLowDays 根K线，含端点），
 * 切片链上任一天都不得为涨停（相对前收涨幅 >= limitUpThreshold%），
 * 按每 pointsPerNode 个收盘价取均值整合成一个节点（末尾不足一组向前借用补足），
 * 判定节点链逐步上涨（允许偶尔偏差）：
 * 整体上涨（末节点 > 首节点），偏离次数 <= ceil(节点数 * maxDeviationRatio)，
 * 偏离程度（偏离累计跌幅 / (最高点-最低点)）<= maxDeviationDegree。
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
