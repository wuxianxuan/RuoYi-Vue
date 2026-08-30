package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);
}