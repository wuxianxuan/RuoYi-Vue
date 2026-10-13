package com.ruoyi.system.service;

/**
 * 趋势积累池减法 Service
 *
 * 对「趋势上涨选股」任务生成的积累分组做减法：逐个复查分组内股票，
 * 将趋势不再向上的股票移除（趋势判定复用趋势上涨选股的「逐步上涨」算法）。
 */
public interface IStockDayTradingService
{
    /**
     * 执行趋势积累池减法，移除分组内趋势不再向上的股票
     */
    void execute();
}
