package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.util.Date;

/**
 * 尾盘推荐运行漏斗 stock_tail_recommend_run
 *
 * 记录每次运行的候选数/命中数/推荐数与各过滤条件的淘汰分布，供调参时判断哪条闸门卡得最狠。
 *
 * @author ruoyi
 */
@Data
public class StockTailRecommendRun
{
    /** 主键 */
    private Long id;

    /** 推荐日（yyyy-MM-dd） */
    private String tradeDate;

    /** 规则版本 */
    private String ruleVersion;

    /** 候选股票数 */
    private Integer candidateCount;

    /** 通过全部过滤的股票数 */
    private Integer hitCount;

    /** 实际推荐数（top-n 截断后） */
    private Integer pickedCount;

    /** 各过滤条件淘汰数量 */
    private String rejectSummary;

    /** 创建时间 */
    private Date createTime;
}
