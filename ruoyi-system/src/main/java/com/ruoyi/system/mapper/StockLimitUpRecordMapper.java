package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);

    /** 查询已采集的最大交易日期（yyyyMMdd），用于增量采集；表为空返回 null */
    String selectMaxTradeDate();
}
