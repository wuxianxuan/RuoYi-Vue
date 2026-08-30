package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 行业/概念资金流向 stock_fund_flow
 *
 * 保存同花顺行业/概念资金流向快照数据（ths_funds_concept.json / ths_funds_industry.json），
 * 用于板块资金流向分析。
 *
 * 唯一键: (plate_type, trade_name, trade_date)
 *
 * @author ruoyi
 */
@Data
public class StockFundFlow
{
    /** 主键 */
    private Long id;

    /** 板块类型（INDUSTRY 行业 / CONCEPT 概念） */
    private String plateType;

    /** 交易日期 */
    private Date tradeDate;

    /** 行业或概念名称 */
    private String tradeName;

    /** 行业或概念详情页链接 */
    private String url;

    /** 行业或概念指数 */
    private BigDecimal price;

    /** 涨跌幅(%) */
    private BigDecimal tradeZdf;

    /** 流入资金(亿) */
    private BigDecimal buy;

    /** 流出资金(亿) */
    private BigDecimal sell;

    /** 净额(亿) */
    private BigDecimal je;

    /** 公司家数 */
    private Integer total;
}
