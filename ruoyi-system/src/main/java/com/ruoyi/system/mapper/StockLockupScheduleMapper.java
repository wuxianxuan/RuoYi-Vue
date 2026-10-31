package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLockupSchedule;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 股票限售解禁日程 Mapper
 *
 * @author ruoyi
 */
public interface StockLockupScheduleMapper
{
    /**
     * 批量插入或更新解禁日程
     */
    int batchUpsert(List<StockLockupSchedule> list);

    /**
     * 查询指定日期区间内的解禁日程
     */
    List<StockLockupSchedule> selectByFreeDateRange(@Param("start") Date start, @Param("end") Date end);

    /**
     * 查询存在大比例解禁风险的股票代码
     *
     * @param start    区间开始（含）
     * @param end      区间结束（含）
     * @param minRatio 解禁比例下限（0~1）
     */
    List<String> selectRiskCodes(@Param("start") Date start,
                                 @Param("end") Date end,
                                 @Param("minRatio") BigDecimal minRatio);

    /**
     * 清理指定日期之前的解禁记录
     */
    int deleteBefore(@Param("date") Date date);
}
