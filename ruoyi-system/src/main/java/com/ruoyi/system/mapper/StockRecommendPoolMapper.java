package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockRecommendPool;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

/**
 * 次日推荐池 Mapper
 *
 * @author ruoyi
 */
public interface StockRecommendPoolMapper
{
    /**
     * 批量插入或更新推荐池
     */
    int batchUpsert(List<StockRecommendPool> list);

    /**
     * 删除指定目标交易日的推荐池（重跑时覆盖）
     */
    int deleteByTargetDate(@Param("targetDate") Date targetDate);

    /**
     * 查询指定目标交易日的推荐池
     */
    List<StockRecommendPool> selectByTargetDate(@Param("targetDate") Date targetDate);

    /**
     * 查询 <= 指定日期的最近一个目标交易日
     */
    Date selectLatestTargetDateBefore(@Param("date") Date date);
}
