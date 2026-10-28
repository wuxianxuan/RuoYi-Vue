package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockTailRecommendRun;

/**
 * 尾盘推荐运行漏斗 Mapper
 *
 * @author ruoyi
 */
public interface StockTailRecommendRunMapper
{
    /**
     * 写入/覆盖某日某规则版本的运行漏斗
     */
    int insertRun(StockTailRecommendRun run);
}
