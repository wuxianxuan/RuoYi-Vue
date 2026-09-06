package com.ruoyi.system.service;

/**
 * 下跌反转选股 Service
 *
 * 算法：查询最近 a 日K线数据，判断最近一天是否为 a 天内最低的收盘价，
 * 若是则继续判断最近一天是否为「缩量下跌」（当日收盘价较前一交易日下跌、且成交量较前一交易日缩量）。
 * 命中股票生成一个新分组。
 */
public interface IStockDeclineReversalService
{
    /**
     * 执行下跌反转选股，命中股票生成一个分组
     */
    void execute();
}
