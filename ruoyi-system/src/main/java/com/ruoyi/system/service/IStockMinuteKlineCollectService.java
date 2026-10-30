package com.ruoyi.system.service;

/**
 * 涨停股分时K线落库 Service
 *
 * 每个交易日收盘后，把「最近 N 个交易日涨停过的股票」当日的分时 K 线写入 stock_kline（kline_type=m5），
 * 这样每只涨停股都会自动攒下「涨停日 + 之后 3 天」的分时数据，供后续分析（例如第三天高开概率）。
 */
public interface IStockMinuteKlineCollectService
{
    /**
     * 落库指定交易日的分时K线
     *
     * @param date 需要落库的交易日（yyyy-MM-dd），为空则默认当天
     */
    void execute(String date);
}
