package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.mapper.StockGroupMapper;
import com.ruoyi.system.service.IStockGroupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockGroupServiceImpl implements IStockGroupService
{
    @Autowired
    private StockGroupMapper stockGroupMapper;

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
    public int insertGroup(StockGroup stockGroup)
    {
        return stockGroupMapper.insertGroup(stockGroup);
    }

    @Override
    public int updateGroup(StockGroup stockGroup)
    {
        return stockGroupMapper.updateGroup(stockGroup);
    }

    @Override
    public int deleteGroupByIds(Long[] ids)
    {
        return stockGroupMapper.deleteGroupByIds(ids);
    }
}