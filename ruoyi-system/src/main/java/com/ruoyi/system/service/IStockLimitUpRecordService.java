package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockLimitUpRequest;

import java.util.List;

public interface IStockLimitUpRecordService {

    int batchSaveStockLimitUpRecords(List<StockLimitUpRecord> records);

    /**
     * 单日涨停数据入库 + 更新股票最新涨停日期/历史最高连板数（同一事务，保证一致性）。
     *
     * @param records 当日涨停记录
     * @param date    交易日 yyyyMMdd
     */
    void saveRecordsAndUpdateStock(List<StockLimitUpRecord> records, String date);

    /**
     * 下载分析数据：遍历日期范围内的每一天，调用财联社API获取涨停分析数据并入库
     *
     * @param request 包含起止日期的请求
     * @return 处理结果摘要
     */
    String syncLimitUpData(StockLimitUpRequest request);
}