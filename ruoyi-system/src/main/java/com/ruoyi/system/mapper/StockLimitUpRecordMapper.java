package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);

    List<StockLimitUpRecord> collectStockData(@Param("dateStr") String dateStr);
}