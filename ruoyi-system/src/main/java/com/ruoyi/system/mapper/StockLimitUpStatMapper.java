package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockLimitUpStat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票近2年涨停次数统计 Mapper
 */
public interface StockLimitUpStatMapper {

    /** 清空统计表 */
    int deleteAll();

    /** 基于涨停记录统计 [startDate, endDate] 区间内各股票涨停次数并写入统计表 */
    int insertStats(@Param("startDate") String startDate, @Param("endDate") String endDate);

    /** 按股票代码批量查询涨停次数统计 */
    List<StockLimitUpStat> selectByCodes(@Param("codes") List<String> codes);
}
