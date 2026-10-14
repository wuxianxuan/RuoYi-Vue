package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockKline;

import java.util.List;

public interface IStockKlineService {
    /**
     * 查询K线（页面查询，直接读本地库，时效性由每日同步任务保证）
     */
    List<StockKline> getKline(String stockCode, String klineType, String startDate, String endDate);

    /**
     * 同步K线：从腾讯源拉取并落库（ON DUPLICATE KEY UPDATE 覆盖更新）
     */
    void syncKline(String stockCode, String market, String klineType, String startDate, String endDate);
}
