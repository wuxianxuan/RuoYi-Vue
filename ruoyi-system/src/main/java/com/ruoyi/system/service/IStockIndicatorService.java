package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockIndicator;
import com.ruoyi.system.domain.stock.StockKline;

import java.util.List;

/**
 * 股票技术指标 Service
 */
public interface IStockIndicatorService
{
    /**
     * 从K线数据计算技术指标
     *
     * @param klines K线数据，按 tradeTime ASC 排序
     * @return 指标列表（与输入K线一一对应，早期不足计算周期的行对应指标为 null）
     */
    List<StockIndicator> calcIndicators(List<StockKline> klines);

    /**
     * 批量持久化指标
     */
    int batchSaveIndicators(List<StockIndicator> indicators);
}
