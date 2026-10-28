package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockTailRecommendRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 尾盘推荐实验记录 Mapper
 *
 * @author ruoyi
 */
public interface StockTailRecommendRecordMapper
{
    /**
     * 批量写入推荐快照（同一推荐日 + 股票代码重复时覆盖指标，便于删除分组后重算）
     */
    int insertBatch(List<StockTailRecommendRecord> list);

    /**
     * 查询待回填次日表现的记录（取最老的若干条，按日期升序）
     */
    List<StockTailRecommendRecord> selectPendingEval(@Param("limit") int limit);

    /**
     * 回填次日表现
     */
    int updateEval(StockTailRecommendRecord record);

    /**
     * 查询最近已回填的记录，用于打印滚动统计
     *
     * @param picked 是否只看被推荐的记录（null=不限）
     * @param limit  样本上限
     */
    List<StockTailRecommendRecord> selectEvaluatedRecent(@Param("picked") Boolean picked, @Param("limit") int limit);
}
