package com.ruoyi.system.domain.stock;

import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

@Data
@EqualsAndHashCode(callSuper = true)
public class StockAnalysisRecord extends BaseEntity {
    private Long id;

    @Excel(name = "交易日期")
    private String tradeDate;

    @Excel(name = "证券代码")
    private String secuCode;

    @Excel(name = "证券名称")
    private String secuName;

    @Excel(name = "涨跌幅")
    private BigDecimal changeRate;

    @Excel(name = "最新价")
    private BigDecimal lastPx;

    @Excel(name = "市值")
    private Long cmc;

    @Excel(name = "时间")
    private String time;

    @Excel(name = "涨停数量")
    private String upNum;

    @Excel(name = "涨停原因")
    private String upReason;

    @Excel(name = "板块代码")
    private String plateSecuCode;

    @Excel(name = "板块名称")
    private String plateSecuName;

    @Excel(name = "板块涨跌幅")
    private BigDecimal plateChangeRate;

    @Excel(name = "板块涨停原因")
    private String plateUpReason;

    @Excel(name = "板块涨停数量")
    private Integer plateStockUpNum;

    @Excel(name = "标签")
    private String upTags;
}