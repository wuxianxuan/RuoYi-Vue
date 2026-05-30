package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockAnalysisRecord;

import java.util.List;

public interface StockAnalysisRecordMapper {
    int batchInsertStockAnalysisRecords(List<StockAnalysisRecord> records);
}