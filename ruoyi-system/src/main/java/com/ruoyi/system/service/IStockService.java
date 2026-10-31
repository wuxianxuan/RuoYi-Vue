package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.Stock;

import java.util.List;

/**
 * 股票基础Service接口
 *
 * @author ruoyi
 */
public interface IStockService
{
    /**
     * 查询股票基础
     */
    Stock selectStockById(Long id);

    /**
     * 查询股票基础列表
     */
    List<Stock> selectStockList(Stock stock);

    /**
     * 基础页受限编辑：只更新备注和所属分组
     *
     * @param id       股票主键
     * @param remark   备注
     * @param groupIds 分组 ID 数组（空数组表示清空分组）
     * @param updateBy 更新人
     * @return 结果
     */
    int updateStockProfile(Long id, String remark, Long[] groupIds, String updateBy);

    /**
     * 根据分组ID查询股票列表
     */
    List<Stock> selectStocksByGroupId(Long groupId);

    /**
     * 查询股票所属的分组ID列表
     */
    Long[] selectGroupIdsByStockCode(String stockCode);

    /**
     * 将股票关联到分组
     */
    int insertStockGroups(String stockCode, Long[] groupIds);
}
