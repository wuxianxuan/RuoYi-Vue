package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 腾讯批量行情快照（不落库，仅用于同步过程传递）
 *
 * 字段与 stock / stock_daily_quote 对齐；speed（涨速）腾讯快照不提供，保持 null。
 *
 * @author ruoyi
 */
@Data
public class StockQuoteSnapshot
{
    /** 股票代码（纯代码） */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 市场：SH / SZ / BJ */
    private String market;

    /** 行情日期（腾讯 qt 第 30 字段的 yyyyMMdd） */
    private LocalDate tradeDate;

    /** 现价 */
    private BigDecimal currentPrice;

    /** 涨跌幅(%) */
    private BigDecimal changeRate;

    /** 涨跌额 */
    private BigDecimal priceChange;

    /** 涨速(%) —— 腾讯快照没有该字段 */
    private BigDecimal speed;

    /** 换手率(%) */
    private BigDecimal turnoverRate;

    /** 量比 */
    private BigDecimal volumeRatio;

    /** 振幅(%) */
    private BigDecimal amplitude;

    /** 成交额（元） */
    private BigDecimal turnoverAmount;

    /** 流通股本（股） */
    private BigDecimal circulatingShares;

    /** 流通市值（元） */
    private BigDecimal circMarketCap;

    /** 市盈率 */
    private BigDecimal peRatio;
}
