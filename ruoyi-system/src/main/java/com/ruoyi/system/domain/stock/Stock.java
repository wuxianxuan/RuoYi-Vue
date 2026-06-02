package com.ruoyi.system.domain.stock;

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
                .append("createBy", getCreateBy())
                .append("createTime", getCreateTime())
                .append("updateBy", getUpdateBy())
                .append("updateTime", getUpdateTime())
                .append("remark", getRemark())
                .toString();
    }
}