package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockAnalysisRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockAnalysisRecordMapper {
    int batchInsertStockAnalysisRecords(List<StockAnalysisRecord> records);

    List<StockAnalysisRecord> collectStockData(@Param("dateStr") String dateStr);
}