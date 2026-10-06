package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.mapper.StockGroupMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
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
    @Autowired
    private StockGroupMapper stockGroupMapper;
    @Autowired
    private StockKlineMapper stockKlineMapper;

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
        // 分页后二次查询填充概念/行业名称
        if (list != null && !list.isEmpty()) {
            List<String> stockCodes = list.stream().map(Stock::getStockCode).collect(java.util.stream.Collectors.toList());

            // 概念名称（联表 stock_plate_stock）
            List<Map<String, Object>> conceptMappings = stockPlateMapper.selectConceptNamesByStockCodes(stockCodes);
            Map<String, List<String>> conceptMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : conceptMappings) {
                String stockCode = (String) mapping.get("stockCode");
                String conceptName = (String) mapping.get("conceptName");
                conceptMap.computeIfAbsent(stockCode, k -> new java.util.ArrayList<>()).add(conceptName);
            }

            // 行业名称（联表 stock_plate_stock，取叶子行业名）
            List<Map<String, Object>> industryMappings = stockPlateMapper.selectIndustryNamesByStockCodes(stockCodes);
            Map<String, List<String>> industryMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : industryMappings) {
                String stockCode = (String) mapping.get("stockCode");
                String industryName = (String) mapping.get("industryName");
                industryMap.computeIfAbsent(stockCode, k -> new java.util.ArrayList<>()).add(industryName);
            }

            // 所属分组名称（联表 stock_group_stock）
            List<Map<String, Object>> groupMappings = stockGroupMapper.selectGroupNamesByStockCodes(stockCodes);
            Map<String, List<String>> groupMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : groupMappings) {
                String stockCode = (String) mapping.get("stockCode");
                String groupName = (String) mapping.get("groupName");
                groupMap.computeIfAbsent(stockCode, k -> new java.util.ArrayList<>()).add(groupName);
            }

            // 年初至今涨幅基准价（联表 stock_kline，取上年最后一个交易日收盘价）
            String yearStartDate = LocalDate.now().getYear() + "-01-01";
            List<Map<String, Object>> ytdMappings = stockKlineMapper.selectYearStartCloseByStockCodes(stockCodes, yearStartDate);
            Map<String, BigDecimal> ytdBaseMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : ytdMappings) {
                String stockCode = (String) mapping.get("stockCode");
                Object closePrice = mapping.get("closePrice");
                if (stockCode != null && closePrice != null) {
                    ytdBaseMap.put(stockCode, new BigDecimal(closePrice.toString()));
                }
            }

            for (Stock s : list) {
                List<String> names = conceptMap.getOrDefault(s.getStockCode(), java.util.Collections.emptyList());
                s.setConceptNames(names);
                s.setConceptNamesStr(String.join("、", names));

                List<String> industryNames = industryMap.getOrDefault(s.getStockCode(), java.util.Collections.emptyList());
                s.setIndustryName(industryNames.isEmpty() ? null : String.join("、", industryNames));

                List<String> groupNames = groupMap.getOrDefault(s.getStockCode(), java.util.Collections.emptyList());
                s.setGroupNames(groupNames);
                s.setGroupNamesStr(String.join("、", groupNames));

                BigDecimal ytdBase = ytdBaseMap.get(s.getStockCode());
                if (s.getCurrentPrice() != null && ytdBase != null && ytdBase.compareTo(BigDecimal.ZERO) != 0) {
                    s.setYtdChangeRate(s.getCurrentPrice().subtract(ytdBase)
                            .multiply(new BigDecimal("100"))
                            .divide(ytdBase, 2, RoundingMode.HALF_UP));
                }
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
    public Long[] selectGroupIdsByStockCode(String stockCode)
    {
        return stockMapper.selectGroupIdsByStockCode(stockCode);
    }

    @Override
    public int insertStockGroups(String stockCode, Long[] groupIds)
    {
        stockMapper.deleteStockGroupsByStockCode(stockCode);
        if (groupIds != null && groupIds.length > 0)
        {
            return stockMapper.insertStockGroups(stockCode, groupIds);
        }
        return 0;
    }

    @Override
    public List<Stock> autocompleteStockCode(String keyword)
    {
        return stockMapper.selectStockByCodePrefix(keyword);
    }
}
