package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 股票技术指标缓存
 */
@Data
public class StockIndicator
{
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** K线类型（D=日 W=周 M=月） */
    private String klineType;

    /** 交易日期 */
    private Date tradeTime;

    /** 5日均线 */
    private BigDecimal ma5;

    /** 10日均线 */
    private BigDecimal ma10;

    /** 20日均线 */
    private BigDecimal ma20;

    /** 60日均线 */
    private BigDecimal ma60;

    /** MACD DIF */
    private BigDecimal macdDif;

    /** MACD DEA */
    private BigDecimal macdDea;

    /** MACD 柱（BAR = 2 * (DIF - DEA)） */
    private BigDecimal macdBar;

    /** 6日RSI */
    private BigDecimal rsi6;

    /** 14日RSI */
    private BigDecimal rsi14;

    /** 量比（当日成交量 / 5日均量） */
    private BigDecimal volRatio;

    /** 趋势斜率（线性回归） */
    private BigDecimal trendSlope;
}
