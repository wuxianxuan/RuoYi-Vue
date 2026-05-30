package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class StockKline {
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** K线类型（D=日 W=周 M=月 1/5/15/30/60=分钟） */
    private String klineType;

    /** K线时间点 */
    private Date tradeTime;

    /** 开盘价 */
    private BigDecimal openPrice;

    /** 收盘价 */
    private BigDecimal closePrice;

    /** 最高价 */
    private BigDecimal highPrice;

    /** 最低价 */
    private BigDecimal lowPrice;

    /** 成交量（股） */
    private Long volume;

    /** 成交额 */
    private BigDecimal amount;
}
