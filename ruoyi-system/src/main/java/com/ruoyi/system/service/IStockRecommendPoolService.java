package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockRecommendPool;

import java.time.LocalDate;
import java.util.List;

/**
 * 次日推荐池 Service
 *
 * @author ruoyi
 */
public interface IStockRecommendPoolService
{
    /**
     * 生成指定交易日的次日推荐池（趋势上升 + 涨停首二板，剔除大比例解禁风险）
     *
     * @param date 数据截止交易日
     */
    void buildPool(LocalDate date);

    /**
     * 查询指定目标交易日的推荐池
     */
    List<StockRecommendPool> selectByTargetDate(LocalDate targetDate);

    /**
     * 查询 <= 指定日期的最近一个推荐池目标交易日
     */
    LocalDate latestTargetDate(LocalDate date);
}
