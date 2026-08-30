package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockFundFlow;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 行业/概念资金流向 Mapper
 *
 * @author ruoyi
 */
public interface StockFundFlowMapper
{
    /**
     * 批量插入资金流向（INSERT IGNORE，已存在则跳过保留历史）
     */
    int batchInsertIgnore(List<StockFundFlow> list);

    /**
     * 查询某板块类型下最近的交易日（去重、倒序）
     */
    List<Date> selectRecentTradeDates(@Param("plateType") String plateType, @Param("limit") int limit);

    /**
     * 查询某板块类型在指定交易日内的资金流向（按交易日升序）
     */
    List<StockFundFlow> selectByPlateTypeAndDates(@Param("plateType") String plateType, @Param("dates") List<Date> dates);
}
