package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockRisingLow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockRisingLowMapper {
    /**
     * 批量插入最低点日期记录（INSERT IGNORE，同股票同最低日期仅保留一条）
     */
    public int insertIgnoreBatch(List<StockRisingLow> list);

    /**
     * 批量查询每只股票最近一个最低日及其收盘价（返回 stockCode / lowDate / closePrice）
     */
    public List<java.util.Map<String, Object>> selectLatestLowWithClose(@Param("stockCodes") List<String> stockCodes);
}
