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
     * 是否存在子板块
     */
    public int hasChildByParentId(Long parentId);

    /**
     * 查询板块下的关联股票
     */
    public List<Stock> selectPlateStocksByPlateId(Long plateId);

    /**
     * 查询股票关联的板块ID列表
     */
    public List<Long> selectPlateIdsByStockId(Long stockId);

    /**
     * 批量添加板块-股票关联
     */
    public int insertPlateStocks(@Param("plateId") Long plateId, @Param("stockIds") Long[] stockIds);

    /**
     * 批量删除板块-股票关联（按 stockId 列表）
     */
    public int deletePlateStocks(@Param("plateId") Long plateId, @Param("stockIds") Long[] stockIds);

    /**
     * 删除板块下所有股票关联
     */
    public int deletePlateStocksByPlateId(Long plateId);

    /**
     * 根据股票代码批量匹配股票
     */
    public List<Stock> selectStocksByCodeBatch(@Param("stockCodes") List<String> stockCodes);

    /**
     * 查询后代板块ID列表（通过 FIND_IN_SET）
     */
    public List<Long> selectDescendantPlateIds(@Param("plateId") Long plateId);

    /**
     * 根据 ancestors 查询行业全路径板块名称
     */
    public List<StockPlate> selectPlatesByIds(@Param("ids") List<Long> ids);

    /**
     * 查询股票关联的行业板块ID
     */
    public List<Long> selectIndustryPlateIdsByStockId(@Param("stockId") Long stockId);

    /**
     * 更新股票的 industry_name
     */
    public int updateStockIndustryName(@Param("stockId") Long stockId, @Param("industryName") String industryName);

    /**
     * 批量查询概念关联（按 stock_id IN 列表，返回 stock_id 和概念名称）
     */
    public List<java.util.Map<String, Object>> selectConceptNamesByStockIds(@Param("stockIds") List<Long> stockIds);

    /**
     * 按名称+类型精确查询板块
     */
    public StockPlate selectPlateByNameAndType(@Param("plateName") String plateName, @Param("plateType") String plateType);
}