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

    public int insertGroupStocks(@Param("groupId") Long groupId, @Param("stockIds") Long[] stockIds);

    public int deleteGroupStocks(@Param("groupId") Long groupId, @Param("stockIds") Long[] stockIds);

    public int deleteGroupStocksByGroupId(Long groupId);
}
