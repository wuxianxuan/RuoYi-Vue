package com.ruoyi.system.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 股票推荐引擎 Service
 */
public interface IStockRecommendService
{
    /**
     * 获取板块热度（某板块在某交易日涨停的股票数）
     *
     * @param plateName 板块名称
     * @param tradeDate 交易日期 (yyyyMMdd)
     * @return 涨停股票数
     */
    int getPlateHeat(String plateName, String tradeDate);

    /**
     * 获取股票所属板块的平均热度
     *
     * @param stockCode 股票代码
     * @param tradeDate 交易日期 (yyyyMMdd)
     * @return 板块平均热度
     */
    BigDecimal getStockPlateHeat(String stockCode, String tradeDate);

    /**
     * 执行推荐引擎
     *
     * @param tradeDate 推荐日期 (yyyy-MM-dd)
     * @param topN      取前 N 条结果
     * @return 生成的推荐数量
     */
    int executeRecommend(String tradeDate, int topN);
}
