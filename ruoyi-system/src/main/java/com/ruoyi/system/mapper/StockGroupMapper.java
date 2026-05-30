package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockGroup;

import java.util.List;

public interface StockGroupMapper {
    public StockGroup selectGroupById(Long id);

    public List<StockGroup> selectGroupList(StockGroup stockGroup);

    public int insertGroup(StockGroup stockGroup);

    public int updateGroup(StockGroup stockGroup);

    public int deleteGroupById(Long id);

    public int deleteGroupByIds(Long[] ids);
}
