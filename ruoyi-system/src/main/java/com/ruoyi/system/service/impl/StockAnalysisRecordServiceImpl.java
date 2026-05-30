package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockAnalysisRecord;
import com.ruoyi.system.mapper.StockAnalysisRecordMapper;
import com.ruoyi.system.service.IStockAnalysisRecordService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockAnalysisRecordServiceImpl implements IStockAnalysisRecordService {

    @Resource
    private StockAnalysisRecordMapper stockAnalysisRecordMapper;

    @Override
    public int batchSaveStockAnalysisRecords(List<StockAnalysisRecord> records) {
        return stockAnalysisRecordMapper.batchInsertStockAnalysisRecords(records);
    }
}