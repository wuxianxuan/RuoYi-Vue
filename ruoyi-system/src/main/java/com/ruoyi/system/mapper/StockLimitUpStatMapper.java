package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpStat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票近30天涨停次数统计 Mapper
 */
public interface StockLimitUpStatMapper {

    /** 清空统计表 */
    int deleteAll();

    /** 聚合 [startDate, endDate] 区间内各股票涨停次数（secu_code 已在采集阶段归一化为纯代码） */
    List<StockLimitUpStat> selectAggregatedByRange(@Param("startDate") String startDate, @Param("endDate") String endDate);

    /** 批量写入统计表 */
    int batchInsert(List<StockLimitUpStat> list);

    /** 按股票代码批量查询涨停次数统计 */
    List<StockLimitUpStat> selectByCodes(@Param("codes") List<String> codes);
}
