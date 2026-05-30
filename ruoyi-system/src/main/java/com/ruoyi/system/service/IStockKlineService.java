package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockKline;

import java.util.List;

public interface IStockKlineService {
    public List<StockKline> getKline(String stockCode, String market, String klineType,
                                     String startDate, String endDate);

    public void syncTodayKline(String stockCode, String market);
}
