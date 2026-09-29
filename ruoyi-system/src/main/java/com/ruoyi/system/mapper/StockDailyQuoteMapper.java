package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockDailyQuote;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 个股每日行情快照 Mapper
 *
 * @author ruoyi
 */
public interface StockDailyQuoteMapper
{
    /**
     * 批量插入每日行情快照（INSERT IGNORE，已存在则跳过保留历史）
     */
    int batchInsertIgnore(List<StockDailyQuote> list);

    /**
     * 查询最近 limit 个交易日期（去重、按日期降序）
     */
    List<Date> selectRecentTradeDates(@Param("limit") int limit);

    /**
     * 查询指定交易日内的每日行情快照
     */
    List<StockDailyQuote> selectByTradeDates(@Param("dates") List<Date> dates);
}
