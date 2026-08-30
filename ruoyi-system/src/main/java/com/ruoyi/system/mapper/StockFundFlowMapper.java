package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockFundFlow;

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
}
