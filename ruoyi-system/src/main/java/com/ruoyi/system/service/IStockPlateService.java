package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;

import java.util.List;
import java.util.Map;

/**
 * 板块字典Service接口
 *
 * @author ruoyi
 */
public interface IStockPlateService {

    /**
     * 查询板块列表
     */
    public List<StockPlate> selectPlateList(StockPlate stockPlate);

    /**
     * 查询板块详情
     */
    public StockPlate selectPlateById(Long id);

    /**
     * 新增板块
     */
    public int insertPlate(StockPlate stockPlate);

    /**
     * 修改板块
     */
    public int updatePlate(StockPlate stockPlate);

    /**
     * 批量删除板块
     */
    public int deletePlateByIds(Long[] ids);

    /**
     * 查询所有行业板块（扁平列表，平铺一级）
     */
    public List<StockPlate> selectIndustryPlateList();

    /**
     * 查询所有概念板块（扁平列表，平铺一级）
     */
    public List<StockPlate> selectConceptPlateList();

    /**
     * 查询板块下的股票列表
     */
    public List<Stock> selectPlateStocks(String plateCode);

    /**
     * 批量添加股票到板块
     */
    public int insertPlateStocks(String plateCode, String[] stockCodes);

    /**
     * 全量替换板块-股票关联（先删后插，同一事务），避免同步任务中断时关联丢失
     */
    public int replacePlateStocks(String plateCode, String[] stockCodes);

    /**
     * 批量从板块移除股票
     */
    public int deletePlateStocks(String plateCode, String[] stockCodes);

    /**
     * 解析粘贴的股票代码文本
     *
     * @param text 粘贴的文本
     * @return { matched: [{stockCode, stockName}], unmatched: [String] }
     */
    public Map<String, Object> parseStockCodes(String text);

    /**
     * 批量修改板块的重点关注标记
     *
     * @param ids       板块ID列表
     * @param focusFlag 重点关注标记（1 需要 / 0 不需要）
     */
    public int updateFocusFlag(Long[] ids, Integer focusFlag);
}
