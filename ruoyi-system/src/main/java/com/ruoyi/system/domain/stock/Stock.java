package com.ruoyi.system.domain.stock;

import java.math.BigDecimal;
import java.util.Date;
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
    @Excel(name = "股票代码", sort = 1)
    private String stockCode;

    /** 股票名称 */
    @Excel(name = "股票名称", sort = 2)
    private String stockName;

    /** 市场：SH / SZ / BJ / HK / US */
    @Excel(name = "市场", sort = 3)
    private String market;

    /** 行业名称（非持久化，联表 stock_plate_stock 填充，取叶子行业名） */
    @Excel(name = "行业", sort = 4)
    private String industryName;

    /** 概念名称列表（非持久化，列表展示用） */
    private List<String> conceptNames;

    /** 概念名称（顿号拼接，用于导出） */
    @Excel(name = "概念", width = 30, sort = 5)
    private String conceptNamesStr;

    /** 所属分组名称列表（非持久化，联表 stock_group_stock 填充） */
    private List<String> groupNames;

    /** 所属分组名称（顿号拼接，用于导出） */
    @Excel(name = "所属分组", width = 20, sort = 6)
    private String groupNamesStr;

    /** 现价 */
    @Excel(name = "现价", sort = 7)
    private BigDecimal currentPrice;

    /** 涨跌幅(%) */
    @Excel(name = "涨跌幅(%)", sort = 8)
    private BigDecimal changeRate;

    /** 涨跌 */
    @Excel(name = "涨跌", sort = 9)
    private BigDecimal priceChange;

    /** 涨速(%) */
    @Excel(name = "涨速(%)", sort = 10)
    private BigDecimal speed;

    /** 换手率(%) */
    @Excel(name = "换手(%)", sort = 11)
    private BigDecimal turnoverRate;

    /** 量比 */
    @Excel(name = "量比", sort = 12)
    private BigDecimal volumeRatio;

    /** 振幅(%) */
    @Excel(name = "振幅(%)", sort = 13)
    private BigDecimal amplitude;

    /** 成交额 */
    @Excel(name = "成交额", sort = 14)
    private BigDecimal turnoverAmount;

    /** 流通股 */
    @Excel(name = "流通股", sort = 15)
    private BigDecimal circulatingShares;

    /** 流通市值 */
    @Excel(name = "流通市值", sort = 16)
    private BigDecimal circMarketCap;

    /** 市盈率（PE） */
    @Excel(name = "市盈率", sort = 17)
    private BigDecimal peRatio;

    /** 行业筛选参数（非持久化，支持多选） */
    private Long[] industryIds;

    /** 概念筛选参数（非持久化，支持多选） */
    private Long[] conceptIds;

    /** 分组筛选参数（非持久化，支持多选） */
    private Long[] groupIds;

    /** 涨停数（近30天，含今天，列表展示用，非持久化） */
    private Integer limitUpCount;

    /** 涨停数下限筛选参数（非持久化，查询涨停数 >= 该值的数据） */
    private Integer minLimitUpCount;

    /** 最近涨停日期（非持久化，联表 stock_limit_up_record 取最近一次涨停交易日） */
    private Date lastLimitUpDate;

    /** 筛选参数：仅显示最近一个交易日涨停的股票（非持久化，1=启用） */
    private Integer limitUpLatest;

    /** 现价下限筛选参数（非持久化） */
    private BigDecimal minCurrentPrice;

    /** 现价上限筛选参数（非持久化） */
    private BigDecimal maxCurrentPrice;

    /** 涨跌幅下限筛选参数（非持久化） */
    private BigDecimal minChangeRate;

    /** 涨跌幅上限筛选参数（非持久化） */
    private BigDecimal maxChangeRate;

    /** 换手率下限筛选参数（非持久化） */
    private BigDecimal minTurnoverRate;

    /** 换手率上限筛选参数（非持久化） */
    private BigDecimal maxTurnoverRate;

    /** 量比下限筛选参数（非持久化） */
    private BigDecimal minVolumeRatio;

    /** 量比上限筛选参数（非持久化） */
    private BigDecimal maxVolumeRatio;

    /** 成交额下限筛选参数（非持久化） */
    private BigDecimal minTurnoverAmount;

    /** 成交额上限筛选参数（非持久化） */
    private BigDecimal maxTurnoverAmount;

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

    public void setSpeed(BigDecimal speed) { this.speed = speed; }
    public BigDecimal getSpeed() { return speed; }

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

    public String getConceptNamesStr()
    {
        return conceptNamesStr;
    }

    public void setConceptNamesStr(String conceptNamesStr)
    {
        this.conceptNamesStr = conceptNamesStr;
    }

    public List<String> getGroupNames()
    {
        return groupNames;
    }

    public void setGroupNames(List<String> groupNames)
    {
        this.groupNames = groupNames;
    }

    public String getGroupNamesStr()
    {
        return groupNamesStr;
    }

    public void setGroupNamesStr(String groupNamesStr)
    {
        this.groupNamesStr = groupNamesStr;
    }

    public Long[] getIndustryIds()
    {
        return industryIds;
    }

    public void setIndustryIds(Long[] industryIds)
    {
        this.industryIds = industryIds;
    }

    public Long[] getConceptIds()
    {
        return conceptIds;
    }

    public void setConceptIds(Long[] conceptIds)
    {
        this.conceptIds = conceptIds;
    }

    public Long[] getGroupIds()
    {
        return groupIds;
    }

    public void setGroupIds(Long[] groupIds)
    {
        this.groupIds = groupIds;
    }

    public Integer getLimitUpCount()
    {
        return limitUpCount;
    }

    public void setLimitUpCount(Integer limitUpCount)
    {
        this.limitUpCount = limitUpCount;
    }

    public Integer getMinLimitUpCount()
    {
        return minLimitUpCount;
    }

    public void setMinLimitUpCount(Integer minLimitUpCount)
    {
        this.minLimitUpCount = minLimitUpCount;
    }

    public Date getLastLimitUpDate() { return lastLimitUpDate; }
    public void setLastLimitUpDate(Date lastLimitUpDate) { this.lastLimitUpDate = lastLimitUpDate; }

    public Integer getLimitUpLatest() { return limitUpLatest; }
    public void setLimitUpLatest(Integer limitUpLatest) { this.limitUpLatest = limitUpLatest; }

    public BigDecimal getMinCurrentPrice() { return minCurrentPrice; }
    public void setMinCurrentPrice(BigDecimal minCurrentPrice) { this.minCurrentPrice = minCurrentPrice; }

    public BigDecimal getMaxCurrentPrice() { return maxCurrentPrice; }
    public void setMaxCurrentPrice(BigDecimal maxCurrentPrice) { this.maxCurrentPrice = maxCurrentPrice; }

    public BigDecimal getMinChangeRate() { return minChangeRate; }
    public void setMinChangeRate(BigDecimal minChangeRate) { this.minChangeRate = minChangeRate; }

    public BigDecimal getMaxChangeRate() { return maxChangeRate; }
    public void setMaxChangeRate(BigDecimal maxChangeRate) { this.maxChangeRate = maxChangeRate; }

    public BigDecimal getMinTurnoverRate() { return minTurnoverRate; }
    public void setMinTurnoverRate(BigDecimal minTurnoverRate) { this.minTurnoverRate = minTurnoverRate; }

    public BigDecimal getMaxTurnoverRate() { return maxTurnoverRate; }
    public void setMaxTurnoverRate(BigDecimal maxTurnoverRate) { this.maxTurnoverRate = maxTurnoverRate; }

    public BigDecimal getMinVolumeRatio() { return minVolumeRatio; }
    public void setMinVolumeRatio(BigDecimal minVolumeRatio) { this.minVolumeRatio = minVolumeRatio; }

    public BigDecimal getMaxVolumeRatio() { return maxVolumeRatio; }
    public void setMaxVolumeRatio(BigDecimal maxVolumeRatio) { this.maxVolumeRatio = maxVolumeRatio; }

    public BigDecimal getMinTurnoverAmount() { return minTurnoverAmount; }
    public void setMinTurnoverAmount(BigDecimal minTurnoverAmount) { this.minTurnoverAmount = minTurnoverAmount; }

    public BigDecimal getMaxTurnoverAmount() { return maxTurnoverAmount; }
    public void setMaxTurnoverAmount(BigDecimal maxTurnoverAmount) { this.maxTurnoverAmount = maxTurnoverAmount; }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
                .append("id", getId())
                .append("stockCode", getStockCode())
                .append("stockName", getStockName())
                .append("market", getMarket())
                .append("industryName", getIndustryName())
                .append("peRatio", getPeRatio())
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