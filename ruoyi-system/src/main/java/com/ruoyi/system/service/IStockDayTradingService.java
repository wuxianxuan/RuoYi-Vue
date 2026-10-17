package com.ruoyi.system.service;

/**
 * 趋势积累池减法 + 日推荐 Service
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法并产出当日推荐：
 * 先比较当天收盘价与入积累池后最近一个最低日期的收盘价，走下坡的移出积累池；
 * 再对剩余股票做最近趋势过滤（最近两日连续下跌，或当前价低于前天开盘价），
 * 剩下的生成当天日推荐分组。
 */
public interface IStockDayTradingService
{
    /**
     * 执行趋势积累池减法，并生成当天日推荐分组
     */
    void execute();
}
