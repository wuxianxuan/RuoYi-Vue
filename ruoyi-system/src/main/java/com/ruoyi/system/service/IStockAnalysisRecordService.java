package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockAnalysisRecord;

import java.util.List;

public interface IStockAnalysisRecordService {
    int batchSaveStockAnalysisRecords(List<StockAnalysisRecord> records);
}