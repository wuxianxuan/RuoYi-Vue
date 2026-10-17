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

    public int insertGroupStocks(Long groupId, String[] stockCodes);

    /**
     * 增量插入分组股票（INSERT IGNORE，主键 (group_id, stock_code) 去重，重复忽略）
     * 用于趋势累加等场景，避免读-改-全量写并发丢数据。
     */
    public int insertGroupStocksIgnore(Long groupId, String[] stockCodes);

    public int deleteGroupStocks(Long groupId, String[] stockCodes);

    public List<com.ruoyi.system.domain.stock.Stock> selectStocksNotInGroup(Long groupId, com.ruoyi.system.domain.stock.Stock stock);
}
