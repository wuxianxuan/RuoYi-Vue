package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockLimitUpRecord;
import com.ruoyi.system.domain.stock.StockLimitUpRequest;

import java.util.List;

public interface IStockLimitUpRecordService {

    int batchSaveStockLimitUpRecords(List<StockLimitUpRecord> records);

    /**
     * 下载分析数据：遍历日期范围内的每一天，调用财联社API获取涨停分析数据并入库
     *
     * @param request 包含起止日期的请求
     * @return 处理结果摘要
     */
    String downloadAnalysisData(StockLimitUpRequest request);
}