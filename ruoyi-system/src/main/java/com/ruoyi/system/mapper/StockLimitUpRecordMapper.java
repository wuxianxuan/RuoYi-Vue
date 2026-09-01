package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);

    /**
     * 查询指定日期范围内出现涨停、且所属行业/概念为重点关注板块（focus_flag=1）的股票代码（去重）
     *
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   截止日期 yyyyMMdd
     */
    List<String> selectLimitUpCodesInFocusPlate(@Param("startDate") String startDate, @Param("endDate") String endDate);
}