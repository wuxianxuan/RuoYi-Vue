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
     * 查询所有行业板块（扁平列表，供前端构建树）
     */
    public List<StockPlate> selectIndustryPlateList();

    /**
     * 是否存在子板块
     */
    public boolean hasChildByParentId(Long parentId);

    /**
     * 查询板块下的股票列表
     */
    public List<Stock> selectPlateStocks(Long plateId);

    /**
     * 批量添加股票到板块
     */
    public int insertPlateStocks(Long plateId, Long[] stockIds);

    /**
     * 批量从板块移除股票
     */
    public int deletePlateStocks(Long plateId, Long[] stockIds);

    /**
     * 解析粘贴的股票代码文本
     *
     * @param text 粘贴的文本
     * @return { matched: [{id, stockCode, stockName}], unmatched: [String] }
     */
    public Map<String, Object> parseStockCodes(String text);
}
