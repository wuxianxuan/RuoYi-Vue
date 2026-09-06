package com.ruoyi.system.mapper;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 板块字典Mapper接口
 *
 * @author ruoyi
 */
public interface StockPlateMapper {

    /**
     * 查询板块列表
     */
    public List<StockPlate> selectPlateList(StockPlate stockPlate);

    /**
     * 查询板块详情
     */
    public StockPlate selectPlateById(Long id);

    /**
     * 查询所有行业板块（扁平列表，供前端构建树）
     */
    public List<StockPlate> selectIndustryPlateList();

    /**
     * 查询所有概念板块（扁平列表，供前端展示）
     */
    public List<StockPlate> selectConceptPlateList();

    /**
     * 新增板块
     */
    public int insertPlate(StockPlate stockPlate);

    /**
     * 修改板块
     */
    public int updatePlate(StockPlate stockPlate);

    /**
     * 删除板块
     */
    public int deletePlateById(Long id);

    /**
     * 批量删除板块
     */
    public int deletePlateByIds(Long[] ids);

    /**
     * 查询板块下的关联股票
     */
    public List<Stock> selectPlateStocksByPlateCode(@Param("plateCode") String plateCode);

    /**
     * 查询所有已存在股票关联的板块代码（去重）
     */
    public List<String> selectDistinctPlateCodesWithStocks();

    /**
     * 批量添加板块-股票关联
     */
    public int insertPlateStocks(@Param("plateCode") String plateCode, @Param("stockCodes") String[] stockCodes);

    /**
     * 批量删除板块-股票关联（按 stockCode 列表）
     */
    public int deletePlateStocks(@Param("plateCode") String plateCode, @Param("stockCodes") String[] stockCodes);

    /**
     * 删除板块下所有股票关联
     */
    public int deletePlateStocksByPlateCode(@Param("plateCode") String plateCode);

    /**
     * 根据股票代码批量匹配股票
     */
    public List<Stock> selectStocksByCodeBatch(@Param("stockCodes") List<String> stockCodes);

    /**
     * 批量查询概念关联（按 stock_code IN 列表，返回 stock_code 和概念名称）
     */
    public List<java.util.Map<String, Object>> selectConceptNamesByStockCodes(@Param("stockCodes") List<String> stockCodes);

    /**
     * 批量查询行业关联（按 stock_code IN 列表，返回 stock_code 和叶子行业名称）
     */
    public List<java.util.Map<String, Object>> selectIndustryNamesByStockCodes(@Param("stockCodes") List<String> stockCodes);

    /**
     * 按名称+类型精确查询板块
     */
    public StockPlate selectPlateByNameAndType(@Param("plateName") String plateName, @Param("plateType") String plateType);

    /**
     * 批量修改板块的重点关注标记
     */
    public int updateFocusFlagByIds(@Param("ids") Long[] ids, @Param("focusFlag") Integer focusFlag);
}