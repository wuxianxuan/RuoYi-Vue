package com.ruoyi.system.service.impl;

import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.mapper.StockPlateMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockService;

/**
 * 股票基础Service业务层处理
 * 
 * @author ruoyi
 * @date 2026-05-30
 */
@Service
public class StockServiceImpl implements IStockService 
{
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private StockPlateMapper stockPlateMapper;

    /**
     * 查询股票基础
     * 
     * @param id 股票基础主键
     * @return 股票基础
     */
    @Override
    public Stock selectStockById(Long id)
    {
        return stockMapper.selectStockById(id);
    }

    /**
     * 查询股票基础列表
     * 
     * @param stock 股票基础
     * @return 股票基础
     */
    @Override
    public List<Stock> selectStockList(Stock stock)
    {
        List<Stock> list = stockMapper.selectStockList(stock);
        // 分页后二次查询填充概念名称
        if (list != null && !list.isEmpty()) {
            List<Long> stockIds = list.stream().map(Stock::getId).collect(java.util.stream.Collectors.toList());
            List<Map<String, Object>> conceptMappings = stockPlateMapper.selectConceptNamesByStockIds(stockIds);
            Map<Long, List<String>> conceptMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : conceptMappings) {
                Long stockId = ((Number) mapping.get("stockId")).longValue();
                String conceptName = (String) mapping.get("conceptName");
                conceptMap.computeIfAbsent(stockId, k -> new java.util.ArrayList<>()).add(conceptName);
            }
            for (Stock s : list) {
                s.setConceptNames(conceptMap.getOrDefault(s.getId(), java.util.Collections.emptyList()));
            }
        }
        return list;
    }

    /**
     * 新增股票基础
     * 
     * @param stock 股票基础
     * @return 结果
     */
    @Override
    public int insertStock(Stock stock)
    {
        stock.setCreateTime(DateUtils.getNowDate());
        return stockMapper.insertStock(stock);
    }

    /**
     * 修改股票基础
     * 
     * @param stock 股票基础
     * @return 结果
     */
    @Override
    public int updateStock(Stock stock)
    {
        stock.setUpdateTime(DateUtils.getNowDate());
        return stockMapper.updateStock(stock);
    }

    /**
     * 批量删除股票基础
     *
     * @param ids 需要删除的股票基础主键
     * @return 结果
     */
    @Override
    public int deleteStockByIds(Long[] ids)
    {
        return stockMapper.deleteStockByIds(ids);
    }

    /**
     * 删除股票基础信息
     *
     * @param id 股票基础主键
     * @return 结果
     */
    @Override
    public int deleteStockById(Long id)
    {
        return stockMapper.deleteStockById(id);
    }

    @Override
    public List<Stock> selectStocksByGroupId(Long groupId)
    {
        return stockMapper.selectStocksByGroupId(groupId);
    }

    @Override
    public Long[] selectGroupIdsByStockId(Long stockId)
    {
        return stockMapper.selectGroupIdsByStockId(stockId);
    }

    @Override
    public int insertStockGroups(Long stockId, Long[] groupIds)
    {
        stockMapper.deleteStockGroupsByStockId(stockId);
        if (groupIds != null && groupIds.length > 0)
        {
            return stockMapper.insertStockGroups(stockId, groupIds);
        }
        return 0;
    }

    @Override
    public List<Stock> autocompleteStockCode(String keyword)
    {
        return stockMapper.selectStockByCodePrefix(keyword);
    }
}
