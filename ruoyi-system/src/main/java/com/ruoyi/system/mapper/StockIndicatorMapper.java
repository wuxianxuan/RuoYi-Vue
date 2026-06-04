package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockIndicator;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票技术指标 Mapper
 */
public interface StockIndicatorMapper
{
    /**
     * 批量插入或更新（ON DUPLICATE KEY UPDATE）
     */
    int batchInsertOrUpdate(List<StockIndicator> indicators);

    /**
     * 查询某只股票最近的技术指标
     *
     * @param stockCode 股票代码
     * @param klineType K线类型
     * @param startDate 开始日期（含）
     * @param endDate   结束日期（含）
     * @return 指标列表，按 trade_time 升序
     */
    List<StockIndicator> selectByCodeTypeDateRange(@Param("stockCode") String stockCode,
                                                    @Param("klineType") String klineType,
                                                    @Param("startDate") String startDate,
                                                    @Param("endDate") String endDate);

    /**
     * 获取最新一条指标记录
     */
    StockIndicator selectLatestByStockCode(@Param("stockCode") String stockCode,
                                           @Param("klineType") String klineType);
}
