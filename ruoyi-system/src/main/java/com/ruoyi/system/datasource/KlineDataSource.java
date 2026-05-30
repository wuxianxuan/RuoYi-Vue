package com.ruoyi.system.datasource;

import com.ruoyi.system.domain.stock.StockKline;

import java.util.List;

public interface KlineDataSource {
    String getName();

    List<StockKline> fetchKline(String stockCode, String market, String klineType,
                                String startDate, String endDate);
}
