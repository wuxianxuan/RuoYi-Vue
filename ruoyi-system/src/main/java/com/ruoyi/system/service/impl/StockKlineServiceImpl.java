package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockKlineServiceImpl implements IStockKlineService
{
    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    /**
     * 页面查询：直接读本地库，不再调外部数据源
     */
    @Override
    public List<StockKline> getKline(String stockCode, String klineType, String startDate, String endDate)
    {
        return stockKlineMapper.selectByCodeTypeDateRange(stockCode, klineType, startDate, endDate);
    }

    /**
     * 定时同步：从腾讯源拉取并落库，靠唯一键 INSERT IGNORE 幂等去重
     */
    @Override
    public void syncKline(String stockCode, String market, String klineType, String startDate, String endDate)
    {
        List<StockKline> fetched = tencentKlineDataSource.fetchKline(stockCode, market, klineType, startDate, endDate);
        if (fetched != null && !fetched.isEmpty())
        {
            stockKlineMapper.insertIgnoreKlineBatch(fetched);
        }
    }
}
