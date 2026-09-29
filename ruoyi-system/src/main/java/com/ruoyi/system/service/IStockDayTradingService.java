package com.ruoyi.system.service;

/**
 * 适合做T选股 Service
 *
 * 算法：取最近 lookbackDays 个交易日的每日行情快照（stock_daily_quote），
 * 排除创业板/科创板（无交易权限）与 ST/*ST，
 * 对每只股票取近N日「最小日振幅」及换手率/成交额/量比的 平均值，
 * 最小日振幅及三类均值均达到阈值（振幅大、换手率高、成交额大、量比高）即命中，
 * 命中股票生成一个新分组。
 */
public interface IStockDayTradingService
{
    /**
     * 执行适合做T选股，命中股票生成一个分组
     */
    void execute();
}
