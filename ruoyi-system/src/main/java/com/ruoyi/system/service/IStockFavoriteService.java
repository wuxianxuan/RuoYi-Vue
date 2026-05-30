package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockFavorite;

import java.util.List;

public interface IStockFavoriteService {
    public StockFavorite selectFavoriteById(Long id);

    public List<StockFavorite> selectFavoriteList(StockFavorite stockFavorite);

    public int insertFavorite(StockFavorite stockFavorite);

    public int updateFavorite(StockFavorite stockFavorite);

    public int deleteFavoriteByIds(Long[] ids);
}
