package com.ruoyi.system.domain.stock;

import com.ruoyi.common.core.domain.BaseEntity;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 股票推荐结果 stock_recommend
 */
public class StockRecommend extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 推荐日期 */
    private Date recommendDate;

    /** 股票ID */
    private Long stockId;

    /** 股票代码 */
    private String stockCode;

    /** 股票名称 */
    private String stockName;

    /** 综合得分 */
    private BigDecimal totalScore;

    /** 趋势得分 */
    private BigDecimal trendScore;

    /** 板块得分 */
    private BigDecimal plateScore;

    /** 资金得分（预留） */
    private BigDecimal fundScore;

    /** 基本面得分（预留） */
    private BigDecimal baseScore;

    /** 推荐理由 */
    private String reason;

    /** 状态（0待确认 1已确认 2已驳回） */
    private String status;

    // ==================== getters & setters ====================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Date getRecommendDate() { return recommendDate; }
    public void setRecommendDate(Date recommendDate) { this.recommendDate = recommendDate; }

    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public BigDecimal getTotalScore() { return totalScore; }
    public void setTotalScore(BigDecimal totalScore) { this.totalScore = totalScore; }

    public BigDecimal getTrendScore() { return trendScore; }
    public void setTrendScore(BigDecimal trendScore) { this.trendScore = trendScore; }

    public BigDecimal getPlateScore() { return plateScore; }
    public void setPlateScore(BigDecimal plateScore) { this.plateScore = plateScore; }

    public BigDecimal getFundScore() { return fundScore; }
    public void setFundScore(BigDecimal fundScore) { this.fundScore = fundScore; }

    public BigDecimal getBaseScore() { return baseScore; }
    public void setBaseScore(BigDecimal baseScore) { this.baseScore = baseScore; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("recommendDate", getRecommendDate())
                .append("stockId", getStockId())
                .append("stockCode", getStockCode())
                .append("stockName", getStockName())
                .append("totalScore", getTotalScore())
                .append("trendScore", getTrendScore())
                .append("plateScore", getPlateScore())
                .append("reason", getReason())
                .append("status", getStatus())
                .toString();
    }
}
