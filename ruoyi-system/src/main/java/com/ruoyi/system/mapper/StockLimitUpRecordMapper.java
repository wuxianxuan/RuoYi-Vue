package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockLimitUpRecordMapper {
    int batchInsertStockLimitUpRecords(List<StockLimitUpRecord> records);

    List<StockLimitUpRecord> collectStockData(@Param("dateStr") String dateStr);

    /**
     * 查询某日首板涨停的股票（up_num为空，即刚开始涨停的股票，按股票代码去重）
     */
    List<StockLimitUpRecord> selectFirstBoardStocksByDate(@Param("tradeDate") String tradeDate);

    /**
     * 按股票代码列表和日期范围查询涨停记录
     */
    List<StockLimitUpRecord> selectByCodesAndDateRange(@Param("codes") List<String> codes,
                                                        @Param("startDate") String startDate,
                                                        @Param("endDate") String endDate);
}