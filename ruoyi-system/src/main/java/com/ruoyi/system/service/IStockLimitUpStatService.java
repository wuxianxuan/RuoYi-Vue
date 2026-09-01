package com.ruoyi.system.service;

import java.util.List;
import java.util.Map;

/**
 * 股票近30天涨停次数统计 Service
 */
public interface IStockLimitUpStatService {

    /**
     * 重新统计 [startDate, endDate] 区间内各股票的涨停次数，写入统计表
     *
     * @param startDate 起始日期（yyyyMMdd）
     * @param endDate   截止日期（yyyyMMdd）
     */
    void rebuildStats(String startDate, String endDate);

    /**
     * 批量查询股票代码对应的近30天（含今天）涨停次数
     *
     * @param codes 股票代码列表
     * @return 股票代码 -> 涨停次数（缺失的股票不在 Map 中）
     */
    Map<String, Integer> getLimitUpCountMap(List<String> codes);
}
