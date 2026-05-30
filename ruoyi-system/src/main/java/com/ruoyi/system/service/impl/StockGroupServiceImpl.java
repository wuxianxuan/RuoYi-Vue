package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockGroupMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StockGroupServiceImpl implements IStockGroupService
{
    @Autowired
    private StockGroupMapper stockGroupMapper;

    @Autowired
    private StockMapper stockMapper;

    @Override
    public StockGroup selectGroupById(Long id)
    {
        return stockGroupMapper.selectGroupById(id);
    }

    @Override
    public List<StockGroup> selectGroupList(StockGroup stockGroup)
    {
        return stockGroupMapper.selectGroupList(stockGroup);
    }

    @Override
    @Transactional
    public int insertGroup(StockGroup stockGroup)
    {
        int rows = stockGroupMapper.insertGroup(stockGroup);
        Long[] stockIds = stockGroup.getStockIds();
        if (stockIds != null && stockIds.length > 0)
        {
            stockGroupMapper.insertGroupStocks(stockGroup.getId(), stockIds);
        }
        return rows;
    }

    @Override
    @Transactional
    public int updateGroup(StockGroup stockGroup)
    {
        int rows = stockGroupMapper.updateGroup(stockGroup);
        stockGroupMapper.deleteGroupStocksByGroupId(stockGroup.getId());
        Long[] stockIds = stockGroup.getStockIds();
        if (stockIds != null && stockIds.length > 0)
        {
            stockGroupMapper.insertGroupStocks(stockGroup.getId(), stockIds);
        }
        return rows;
    }

    @Override
    @Transactional
    public int deleteGroupByIds(Long[] ids)
    {
        for (Long id : ids)
        {
            stockGroupMapper.deleteGroupStocksByGroupId(id);
        }
        return stockGroupMapper.deleteGroupByIds(ids);
    }

    @Override
    public List<Stock> selectStocksByGroupId(Long groupId)
    {
        return stockMapper.selectStocksByGroupId(groupId);
    }

    @Override
    public int insertGroupStocks(Long groupId, Long[] stockIds)
    {
        if (stockIds != null && stockIds.length > 0)
        {
            return stockGroupMapper.insertGroupStocks(groupId, stockIds);
        }
        return 0;
    }

    @Override
    public int deleteGroupStocks(Long groupId, Long[] stockIds)
    {
        if (stockIds != null && stockIds.length > 0)
        {
            return stockGroupMapper.deleteGroupStocks(groupId, stockIds);
        }
        return 0;
    }

    @Override
    public List<Stock> selectStocksNotInGroup(Long groupId, Stock stock)
    {
        return stockMapper.selectStocksNotInGroup(groupId, stock);
    }
}