package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 股票限售解禁日程 stock_lockup_schedule
 *
 * 数据来源：东方财富 RPT_LIFT_STAGE，用于 119 推荐池的解禁风险过滤。
 *
 * @author ruoyi
 */
@Data
public class StockLockupSchedule
{
    /** 主键 */
    private Long id;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 解禁日 */
    private Date freeDate;

    /** 解禁数量（股） */
    private BigDecimal freeShares;

    /** 解禁市值（元） */
    private BigDecimal liftMarketCap;

    /** 占流通市值比例（0~1） */
    private BigDecimal freeRatio;

    /** 占总市值比例（0~1） */
    private BigDecimal totalRatio;

    /** 限售股类型 */
    private String sharesType;

    /** 解禁股东数 */
    private Integer holderNum;

    /** 数据来源 */
    private String source;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
