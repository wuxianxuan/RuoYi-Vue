package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.StockFavorite;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StockFavoriteMapper {
    public StockFavorite selectFavoriteById(Long id);

    public List<StockFavorite> selectFavoriteList(StockFavorite stockFavorite);

    public int insertFavorite(StockFavorite stockFavorite);

    public int updateFavorite(StockFavorite stockFavorite);

    public int deleteFavoriteById(Long id);

    public int deleteFavoriteByIds(Long[] ids);

    public int insertFavoriteGroup(@Param("favoriteId") Long favoriteId, @Param("groupIds") Long[] groupIds);

    public int deleteFavoriteGroupByFavoriteId(Long favoriteId);

    public Long[] selectGroupIdsByFavoriteId(Long favoriteId);

    public List<StockFavorite> selectFavoritesByGroupId(Long groupId);
}
