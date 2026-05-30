package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.Stock;

import java.util.List;

/**
 * 股票基础Service接口
 * 
 * @author ruoyi
 * @date 2026-05-30
 */
public interface IStockService 
{
    /**
     * 查询股票基础
     * 
     * @param id 股票基础主键
     * @return 股票基础
     */
    public Stock selectStockById(Long id);

    /**
     * 查询股票基础列表
     * 
     * @param stock 股票基础
     * @return 股票基础集合
     */
    public List<Stock> selectStockList(Stock stock);

    /**
     * 新增股票基础
     * 
     * @param stock 股票基础
     * @return 结果
     */
    public int insertStock(Stock stock);

    /**
     * 修改股票基础
     * 
     * @param stock 股票基础
     * @return 结果
     */
    public int updateStock(Stock stock);

    /**
     * 批量删除股票基础
     * 
     * @param ids 需要删除的股票基础主键集合
     * @return 结果
     */
    public int deleteStockByIds(Long[] ids);

    /**
     * 删除股票基础信息
     *
     * @param id 股票基础主键
     * @return 结果
     */
    public int deleteStockById(Long id);

    /**
     * 根据分组ID查询股票列表
     *
     * @param groupId 分组ID
     * @return 股票列表
     */
    public List<Stock> selectStocksByGroupId(Long groupId);

    /**
     * 查询股票所属的分组ID列表
     *
     * @param stockId 股票ID
     * @return 分组ID数组
     */
    public Long[] selectGroupIdsByStockId(Long stockId);

    /**
     * 将股票关联到分组
     *
     * @param stockId 股票ID
     * @param groupIds 分组ID数组
     * @return 结果
     */
    public int insertStockGroups(Long stockId, Long[] groupIds);

    /**
     * 根据股票代码前缀匹配股票（仅SH/SZ市场，最多10条）
     *
     * @param keyword 股票代码前缀
     * @return 股票列表
     */
    public List<Stock> autocompleteStockCode(String keyword);
}
