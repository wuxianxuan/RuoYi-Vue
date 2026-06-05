package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockDailyQuote;

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
}
