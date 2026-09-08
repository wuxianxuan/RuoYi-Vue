package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.Stock;
import org.apache.ibatis.annotations.Param;

import java.util.Date;
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

    public Long[] selectGroupIdsByStockCode(@Param("stockCode") String stockCode);

    public int insertStockGroups(@Param("stockCode") String stockCode, @Param("groupIds") Long[] groupIds);

    public int deleteStockGroupsByStockCode(@Param("stockCode") String stockCode);

    public List<Stock> selectStocksNotInGroup(@Param("groupId") Long groupId, @Param("stock") Stock stock);

    public List<Stock> selectStockByCodePrefix(@Param("keyword") String keyword);

    /**
     * 根据市场列表查询股票
     *
     * @param markets 市场列表
     * @return 股票基础集合
     */
    public List<Stock> selectStocksByMarkets(@Param("markets") List<String> markets);

    /**
     * 批量查询已存在的股票代码（用于判断同步数据中哪些股票需要新增）
     *
     * @param codes 股票代码列表
     * @return 已存在的股票代码集合
     */
    public List<String> selectStockCodesByCodes(@Param("codes") List<String> codes);

    /**
     * 批量更新股票的最新涨停日期（由涨停数据采集任务写入，仅向前不回退）
     *
     * @param stockCodes 当日涨停股票代码列表
     * @param tradeDate  涨停交易日
     */
    public int updateLastLimitUpDate(@Param("stockCodes") List<String> stockCodes, @Param("tradeDate") Date tradeDate);

    /**
     * 更新单只股票的历史最高连板数及对应交易日（由涨停数据采集任务写入，仅当大于当前值才覆盖）
     *
     * @param stockCode  股票代码
     * @param boardCount 本次解析出的连板数
     * @param boardDate  连板数对应的交易日
     */
    public int updateMaxLimitUpBoard(@Param("stockCode") String stockCode, @Param("boardCount") Integer boardCount, @Param("boardDate") Date boardDate);
}
