package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockRecommend;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票推荐结果 Mapper
 */
public interface StockRecommendMapper
{
    /**
     * 批量插入推荐结果
     */
    int batchInsertRecommend(List<StockRecommend> list);

    /**
     * 按日期查询推荐结果
     */
    List<StockRecommend> selectByDate(@Param("recommendDate") String recommendDate);

    /**
     * 按日期删除推荐结果（幂等重跑用）
     */
    int deleteByDate(@Param("recommendDate") String recommendDate);
}
