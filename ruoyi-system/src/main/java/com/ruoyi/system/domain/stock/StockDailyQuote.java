package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 个股每日行情快照 stock_daily_quote
 *
 * 保存同花顺个股行情的历史快照数据（区别于 stock 表的"当前最新"），
 * 用于选股分析、估值趋势、资金流向等需要历史回溯的场景。
 *
 * 唯一键: (stock_code, trade_date)
 *
 * @author ruoyi
 */
@Data
public class StockDailyQuote
{
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 交易日期 */
    private Date tradeDate;

    /** 现价 */
    private BigDecimal currentPrice;

    /** 涨跌幅(%) */
    private BigDecimal changeRate;

    /** 涨跌额 */
    private BigDecimal priceChange;

    /** 涨速(%) */
    private BigDecimal speed;

    /** 换手率(%) */
    private BigDecimal turnoverRate;

    /** 量比 */
    private BigDecimal volumeRatio;

    /** 振幅(%) */
    private BigDecimal amplitude;

    /** 成交额 */
    private BigDecimal turnoverAmount;

    /** 流通股 */
    private BigDecimal circulatingShares;

    /** 流通市值 */
    private BigDecimal circMarketCap;

    /** 市盈率（PE） */
    private BigDecimal peRatio;

    /** 总市值 */
    private BigDecimal totalMarketCap;
}
