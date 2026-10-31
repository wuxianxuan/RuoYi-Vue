package com.ruoyi.system.service;

/**
 * 每日行情快照同步 Service
 *
 * 数据源：腾讯批量行情（qt.gtimg.cn），替代原同花顺 JSON 行情。
 * 写入 stock（最新快照）和 stock_daily_quote（按日历史快照）。
 *
 * @author ruoyi
 */
public interface IStockQuoteSyncService
{
    /**
     * 同步全市场当日行情快照（沪/深/北）
     */
    void syncDailyQuote();
}
