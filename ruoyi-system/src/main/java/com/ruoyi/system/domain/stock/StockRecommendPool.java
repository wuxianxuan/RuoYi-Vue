package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 次日推荐池 stock_recommend_pool
 *
 * 119 盘后生成，117 次日盘中消费。
 *
 * @author ruoyi
 */
@Data
public class StockRecommendPool
{
    /** 主键 */
    private Long id;

    /** 目标交易日 */
    private Date targetDate;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 来源：TREND / LIMIT_UP */
    private String source;

    /** 池内评分 */
    private BigDecimal score;

    /** 波段状态 / 连板描述 */
    private String status;

    /** 入池理由 */
    private String reason;

    /** 扩展信息（JSON） */
    private String extra;

    /** 创建时间 */
    private Date createTime;
}
