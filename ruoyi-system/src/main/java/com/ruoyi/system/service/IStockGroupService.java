package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockGroup;

import java.util.List;

public interface IStockGroupService {
    public StockGroup selectGroupById(Long id);

    public List<StockGroup> selectGroupList(StockGroup stockGroup);

    public int insertGroup(StockGroup stockGroup);

    public int updateGroup(StockGroup stockGroup);

    public int deleteGroupByIds(Long[] ids);

    public List<com.ruoyi.system.domain.stock.Stock> selectStocksByGroupId(Long groupId);

    public int insertGroupStocks(Long groupId, Long[] stockIds);

    public int deleteGroupStocks(Long groupId, Long[] stockIds);

    public List<com.ruoyi.system.domain.stock.Stock> selectStocksNotInGroup(Long groupId, com.ruoyi.system.domain.stock.Stock stock);
}
