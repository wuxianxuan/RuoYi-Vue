package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockGroup;

import java.util.List;

public interface IStockGroupService {
    public StockGroup selectGroupById(Long id);

    public List<StockGroup> selectGroupList(StockGroup stockGroup);

    public int insertGroup(StockGroup stockGroup);

    public int updateGroup(StockGroup stockGroup);

    public int deleteGroupByIds(Long[] ids);
}
