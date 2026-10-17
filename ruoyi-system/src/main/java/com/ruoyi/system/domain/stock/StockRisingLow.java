package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.util.Date;

/**
 * 趋势上涨选股最低点日期记录对象 stock_rising_low
 *
 * @author ruoyi
 */
@Data
public class StockRisingLow {
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 推荐时最低点交易日（yyyy-MM-dd） */
    private String lowDate;

    /** 创建时间 */
    private Date createTime;
}
