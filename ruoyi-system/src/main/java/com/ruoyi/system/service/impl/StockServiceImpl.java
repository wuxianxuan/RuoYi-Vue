package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockGroupMapper;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 股票基础Service业务层处理
 *
 * 股票记录由同步任务维护，页面只允许改备注和所属分组。
 *
 * @author ruoyi
 */
@Service
public class StockServiceImpl implements IStockService
{
    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockGroupMapper stockGroupMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Override
    public Stock selectStockById(Long id)
    {
        return stockMapper.selectStockById(id);
    }

    @Override
    public List<Stock> selectStockList(Stock stock)
    {
        List<Stock> list = stockMapper.selectStockList(stock);
        if (list != null && !list.isEmpty())
        {
            List<String> stockCodes = list.stream().map(Stock::getStockCode)
                    .collect(java.util.stream.Collectors.toList());

            // 所属分组名称（联表 stock_group_stock）
            List<Map<String, Object>> groupMappings = stockGroupMapper.selectGroupNamesByStockCodes(stockCodes);
            Map<String, List<String>> groupMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : groupMappings)
            {
                String stockCode = (String) mapping.get("stockCode");
                String groupName = (String) mapping.get("groupName");
                groupMap.computeIfAbsent(stockCode, k -> new java.util.ArrayList<>()).add(groupName);
            }

            // 年初至今涨幅基准价（联表 stock_kline，取上年最后一个交易日收盘价）
            String yearStartDate = LocalDate.now().getYear() + "-01-01";
            List<Map<String, Object>> ytdMappings = stockKlineMapper.selectYearStartCloseByStockCodes(stockCodes, yearStartDate);
            Map<String, BigDecimal> ytdBaseMap = new java.util.HashMap<>();
            for (Map<String, Object> mapping : ytdMappings)
            {
                String stockCode = (String) mapping.get("stockCode");
                Object closePrice = mapping.get("closePrice");
                if (stockCode != null && closePrice != null)
                {
                    ytdBaseMap.put(stockCode, new BigDecimal(closePrice.toString()));
                }
            }

            for (Stock s : list)
            {
                List<String> groupNames = groupMap.getOrDefault(s.getStockCode(), java.util.Collections.emptyList());
                s.setGroupNames(groupNames);
                s.setGroupNamesStr(String.join("、", groupNames));

                BigDecimal ytdBase = ytdBaseMap.get(s.getStockCode());
                if (s.getCurrentPrice() != null && ytdBase != null && ytdBase.compareTo(BigDecimal.ZERO) != 0)
                {
                    s.setYtdChangeRate(s.getCurrentPrice().subtract(ytdBase)
                            .multiply(new BigDecimal("100"))
                            .divide(ytdBase, 2, RoundingMode.HALF_UP));
                }
            }
        }
        return list;
    }

    /**
     * 基础页受限编辑：只更新备注和所属分组
     */
    @Override
    @Transactional
    public int updateStockProfile(Long id, String remark, Long[] groupIds, String updateBy)
    {
        Stock stock = stockMapper.selectStockById(id);
        if (stock == null)
        {
            throw new ServiceException("股票不存在");
        }
        int rows = stockMapper.updateStockRemark(id, remark, updateBy);
        stockMapper.deleteStockGroupsByStockCode(stock.getStockCode());
        if (groupIds != null && groupIds.length > 0)
        {
            stockMapper.insertStockGroups(stock.getStockCode(), groupIds);
        }
        return rows;
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
    @Transactional
    public int insertStockGroups(String stockCode, Long[] groupIds)
    {
        stockMapper.deleteStockGroupsByStockCode(stockCode);
        if (groupIds != null && groupIds.length > 0)
        {
            return stockMapper.insertStockGroups(stockCode, groupIds);
        }
        return 0;
    }
}
