package com.ruoyi.system.domain.stock;

import java.math.BigDecimal;
import java.util.List;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 股票基础对象 stock
 *
 * @author ruoyi
 * @date 2026-05-30
 */
public class Stock extends BaseEntity
{
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 股票代码 */
    @Excel(name = "股票代码")
    private String stockCode;

    /** 股票名称 */
    @Excel(name = "股票名称")
    private String stockName;

    /** 市场：SH / SZ / BJ / HK / US */
    @Excel(name = "市场：SH / SZ / BJ / HK / US")
    private String market;

    /** 行业全路径（冗余字段） */
    @Excel(name = "行业")
    private String industryName;

    /** 市盈率（PE） */
    private BigDecimal peRatio;

    /** 总市值 */
    private BigDecimal totalMarketCap;

    /** 流通市值 */
    private BigDecimal circMarketCap;

    /** 现价 */
    private BigDecimal currentPrice;

    /** 涨跌幅(%) */
    private BigDecimal changeRate;

    /** 涨跌额 */
    private BigDecimal priceChange;

    /** 换手率(%) */
    private BigDecimal turnoverRate;

    /** 量比 */
    private BigDecimal volumeRatio;

    /** 振幅(%) */
    private BigDecimal amplitude;

    /** 成交额 */
    private BigDecimal turnoverAmount;

    /** 流通股 */
    private BigDecimal circulatingShares;

    /** 概念名称列表（非持久化，列表展示用） */
    private List<String> conceptNames;

    /** 行业筛选参数（非持久化） */
    private Long industryId;

    /** 概念筛选参数（非持久化） */
    private Long[] conceptIds;

    /** 分组筛选参数（非持久化） */
    private Long groupId;

    public void setId(Long id)
    {
        this.id = id;
    }

    public Long getId()
    {
        return id;
    }

    public void setStockCode(String stockCode)
    {
        this.stockCode = stockCode;
    }

    public String getStockCode()
    {
        return stockCode;
    }

    public void setStockName(String stockName)
    {
        this.stockName = stockName;
    }

    public String getStockName()
    {
        return stockName;
    }

    public void setMarket(String market)
    {
        this.market = market;
    }

    public String getMarket()
    {
        return market;
    }

    public void setIndustryName(String industryName)
    {
        this.industryName = industryName;
    }

    public String getIndustryName()
    {
        return industryName;
    }

    public void setPeRatio(BigDecimal peRatio)
    {
        this.peRatio = peRatio;
    }

    public BigDecimal getPeRatio()
    {
        return peRatio;
    }

    public void setTotalMarketCap(BigDecimal totalMarketCap)
    {
        this.totalMarketCap = totalMarketCap;
    }

    public BigDecimal getTotalMarketCap()
    {
        return totalMarketCap;
    }

    public void setCircMarketCap(BigDecimal circMarketCap)
    {
        this.circMarketCap = circMarketCap;
    }

    public BigDecimal getCircMarketCap()
    {
        return circMarketCap;
    }

    public void setCurrentPrice(BigDecimal currentPrice) { this.currentPrice = currentPrice; }
    public BigDecimal getCurrentPrice() { return currentPrice; }

    public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }
    public BigDecimal getChangeRate() { return changeRate; }

    public void setPriceChange(BigDecimal priceChange) { this.priceChange = priceChange; }
    public BigDecimal getPriceChange() { return priceChange; }

    public void setTurnoverRate(BigDecimal turnoverRate) { this.turnoverRate = turnoverRate; }
    public BigDecimal getTurnoverRate() { return turnoverRate; }

    public void setVolumeRatio(BigDecimal volumeRatio) { this.volumeRatio = volumeRatio; }
    public BigDecimal getVolumeRatio() { return volumeRatio; }

    public void setAmplitude(BigDecimal amplitude) { this.amplitude = amplitude; }
    public BigDecimal getAmplitude() { return amplitude; }

    public void setTurnoverAmount(BigDecimal turnoverAmount) { this.turnoverAmount = turnoverAmount; }
    public BigDecimal getTurnoverAmount() { return turnoverAmount; }

    public void setCirculatingShares(BigDecimal circulatingShares) { this.circulatingShares = circulatingShares; }
    public BigDecimal getCirculatingShares() { return circulatingShares; }

    public List<String> getConceptNames()
    {
        return conceptNames;
    }

    public void setConceptNames(List<String> conceptNames)
    {
        this.conceptNames = conceptNames;
    }

    public Long getIndustryId()
    {
        return industryId;
    }

    public void setIndustryId(Long industryId)
    {
        this.industryId = industryId;
    }

    public Long[] getConceptIds()
    {
        return conceptIds;
    }

    public void setConceptIds(Long[] conceptIds)
    {
        this.conceptIds = conceptIds;
    }

    public Long getGroupId()
    {
        return groupId;
    }

    public void setGroupId(Long groupId)
    {
        this.groupId = groupId;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("stockCode", getStockCode())
                .append("stockName", getStockName())
                .append("market", getMarket())
                .append("industryName", getIndustryName())
                .append("peRatio", getPeRatio())
                .append("totalMarketCap", getTotalMarketCap())
                .append("circMarketCap", getCircMarketCap())
                .append("currentPrice", getCurrentPrice())
                .append("changeRate", getChangeRate())
                .append("turnoverRate", getTurnoverRate())
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .append("remark", getRemark())
                .toString();
    }
}