package com.ruoyi.system.service;

/**
 * 分时K线增量落库 Service
 *
 * 由日K同步任务（stockKlineSyncTask.syncKline）在日K同步完成后调用：
 * 把「最近 N 个交易日涨停过的股票 + 趋势上涨池内的股票」的分时 K 线增量写入
 * stock_kline（kline_type=m5），只写比库中已有更晚的 bar，避免每天重复写 7 天数据。
 *
 * 覆盖范围同时服务两类策略：
 *  - 首板/二板回调（涨停股 D~D+3 的分时）
 *  - 趋势上升选股（池内股票的分时，用于后续分析盘口结构）
 */
public interface IStockMinuteKlineCollectService
{
    /**
     * 增量同步分时K线
     *
     * @param days 最近多少个自然日（与日K同步的天数保持一致）
     */
    void syncMinuteKline(int days);
}
