package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockGroup;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockGroupMapper {
    public StockGroup selectGroupById(Long id);

    public List<StockGroup> selectGroupList(StockGroup stockGroup);

    public int insertGroup(StockGroup stockGroup);

    public int updateGroup(StockGroup stockGroup);

    public int deleteGroupById(Long id);

    public int deleteGroupByIds(Long[] ids);

    public int insertGroupStocks(@Param("groupId") Long groupId, @Param("stockCodes") String[] stockCodes);

    public int deleteGroupStocks(@Param("groupId") Long groupId, @Param("stockCodes") String[] stockCodes);

    public int deleteGroupStocksByGroupId(Long groupId);

    /**
     * 批量查询股票所属分组名称（按 stock_code IN 列表，返回 stock_code 和分组名称）
     */
    public List<java.util.Map<String, Object>> selectGroupNamesByStockCodes(@Param("stockCodes") List<String> stockCodes);
}
