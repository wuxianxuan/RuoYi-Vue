package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Date;

/**
 * 股票近30天涨停次数统计对象 stock_limit_up_stat
 *
 * @author ruoyi
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class StockLimitUpStat extends BaseEntity {

    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 近30天涨停次数（含今天，按交易日去重） */
    private Integer limitUpCount;

    /** 统计日期 */
    private Date statDate;
}
