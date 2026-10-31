package com.ruoyi.system.domain.stock;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 尾盘推荐实验记录 stock_tail_recommend_record
 *
 * 记录每次推荐时的指标快照，次日收盘后由 stockTailRecommendEvalTask 回填次日表现，
 * 用于验证推荐口径是否真的降低了次日回调。
 *
 * @author ruoyi
 */
@Data
public class StockTailRecommendRecord
{
    /** 主键 */
    private Long id;

    /** 推荐日（yyyy-MM-dd） */
    private String tradeDate;

    /** 昨收基准日（yyyy-MM-dd） */
    private String baseDate;

    /** 规则版本（用于不同参数组合对比） */
    private String ruleVersion;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 来源：TREND（趋势）/ LIMIT_UP（涨停首二板） */
    private String source;

    /** 是否被推荐：1=推荐，0=仅记录（候选/被挡） */
    private Boolean picked;

    /** 淘汰原因（null=通过全部过滤） */
    private String rejectReason;

    /** 推荐排名（按综合评分降序，1 为最优；仅推荐的有值） */
    private Integer rankNo;

    /** 推荐时价格（14:45 最新价） */
    private BigDecimal recPrice;

    /** 当日开盘涨幅(%) */
    private Double openPct;

    /** 当日涨幅(%) */
    private Double closePct;

    /** 近5日累计涨幅(%) */
    private Double gain5d;

    /** 相对MA5乖离(%) */
    private Double biasMa5;

    /** 收盘位置(0~1) */
    private Double closePos;

    /** 尾盘30分钟涨跌(%) */
    private Double tailChange;

    /** 量比 */
    private Double volumeRatio;

    /** 换手率(%) */
    private Double turnoverRate;

    /** 当日成交额(元，分时估算) */
    private BigDecimal amount;

    /** 综合评分 */
    private Double score;

    /** 次日交易日（yyyy-MM-dd） */
    private String nextTradeDate;

    /** 次日开盘价 */
    private BigDecimal nextOpen;

    /** 次日最高价 */
    private BigDecimal nextHigh;

    /** 次日最低价 */
    private BigDecimal nextLow;

    /** 次日收盘价 */
    private BigDecimal nextClose;

    /** 次日收盘相对推荐价(%) */
    private Double nextChangePct;

    /** 次日最高相对推荐价(%) */
    private Double nextMaxGainPct;

    /** 次日最低相对推荐价(%) */
    private Double nextMaxDrawdownPct;

    /** 回填状态：NULL=待回填，DONE=已回填，NO_DATA=无次日K线 */
    private String evalStatus;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
