package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockFavorite;
import com.ruoyi.system.mapper.StockFavoriteMapper;
import com.ruoyi.system.service.IStockFavoriteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class StockFavoriteServiceImpl implements IStockFavoriteService
{
    @Autowired
    private StockFavoriteMapper stockFavoriteMapper;

    @Override
    public StockFavorite selectFavoriteById(Long id)
    {
        StockFavorite favorite = stockFavoriteMapper.selectFavoriteById(id);
        if (favorite != null)
        {
            favorite.setGroupIds(stockFavoriteMapper.selectGroupIdsByFavoriteId(id));
        }
        return favorite;
    }

    @Override
    public List<StockFavorite> selectFavoriteList(StockFavorite stockFavorite)
    {
        return stockFavoriteMapper.selectFavoriteList(stockFavorite);
    }

    @Override
    @Transactional
    public int insertFavorite(StockFavorite stockFavorite)
    {
        int rows = stockFavoriteMapper.insertFavorite(stockFavorite);
        Long[] groupIds = stockFavorite.getGroupIds();
        if (groupIds != null && groupIds.length > 0)
        {
            stockFavoriteMapper.insertFavoriteGroup(stockFavorite.getId(), groupIds);
        }
        return rows;
    }

    @Override
    @Transactional
    public int updateFavorite(StockFavorite stockFavorite)
    {
        int rows = stockFavoriteMapper.updateFavorite(stockFavorite);
        stockFavoriteMapper.deleteFavoriteGroupByFavoriteId(stockFavorite.getId());
        Long[] groupIds = stockFavorite.getGroupIds();
        if (groupIds != null && groupIds.length > 0)
        {
            stockFavoriteMapper.insertFavoriteGroup(stockFavorite.getId(), groupIds);
        }
        return rows;
    }

    @Override
    @Transactional
    public int deleteFavoriteByIds(Long[] ids)
    {
        for (Long id : ids)
        {
            stockFavoriteMapper.deleteFavoriteGroupByFavoriteId(id);
        }
        return stockFavoriteMapper.deleteFavoriteByIds(ids);
    }
}
