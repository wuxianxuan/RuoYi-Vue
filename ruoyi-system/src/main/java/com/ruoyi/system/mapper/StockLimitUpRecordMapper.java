package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);

    List<StockLimitUpRecord> collectStockData(@Param("dateStr") String dateStr);

    /**
     * 查询某日涨停的股票（按股票代码去重，含名称）
     */
    List<StockLimitUpRecord> selectLimitUpStocksByDate(@Param("tradeDate") String tradeDate);

    /**
     * 按股票代码列表和日期范围查询涨停记录
     */
    List<StockLimitUpRecord> selectByCodesAndDateRange(@Param("codes") List<String> codes,
                                                        @Param("startDate") String startDate,
                                                        @Param("endDate") String endDate);
}