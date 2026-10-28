package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockRisingState;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 趋势上涨选股波段状态 Mapper
 */
public interface StockRisingStateMapper {
    /**
     * 清空波段状态表（每日全量覆盖前先清空）
     */
    public int deleteAll();

    /**
     * 批量写入波段状态（每日全量覆盖）
     */
    public int insertBatch(List<StockRisingState> list);

    /**
     * 按股票代码批量查询波段状态（供日推荐按状态拆分分组）
     */
    public List<StockRisingState> selectByStockCodes(@Param("stockCodes") List<String> stockCodes);

    /**
     * 查询全部波段状态（供尾盘推荐收集上升/震荡候选）
     */
    public List<StockRisingState> selectAll();

    /**
     * 查询波段状态表最后一次更新时间（用于校验候选池是否新鲜），无数据返回 null
     */
    public java.util.Date selectMaxUpdateTime();
}
