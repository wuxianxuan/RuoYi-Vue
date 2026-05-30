package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.Stock;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 股票基础Mapper接口
 * 
 * @author ruoyi
 * @date 2026-05-30
 */
public interface StockMapper 
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
     * 删除股票基础
     * 
     * @param id 股票基础主键
     * @return 结果
     */
    public int deleteStockById(Long id);

    /**
     * 批量删除股票基础
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteStockByIds(Long[] ids);

    void batchInsertListIgnoreSame(List<Stock> insertList);

    public List<Stock> selectStocksByGroupId(Long groupId);

    public Long[] selectGroupIdsByStockId(Long stockId);

    public int insertStockGroups(@Param("stockId") Long stockId, @Param("groupIds") Long[] groupIds);

    public int deleteStockGroupsByStockId(Long stockId);

    public List<Stock> selectStocksNotInGroup(@Param("groupId") Long groupId, @Param("stock") Stock stock);

    public List<Stock> selectStockByCodePrefix(@Param("keyword") String keyword);

    /**
     * 根据市场列表查询股票
     *
     * @param markets 市场列表
     * @return 股票基础集合
     */
    public List<Stock> selectStocksByMarkets(@Param("markets") List<String> markets);
}
