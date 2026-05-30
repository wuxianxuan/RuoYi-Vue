package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.datasource.KlineDataSourceRouter;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StockKlineServiceImpl implements IStockKlineService
{
    private static final Logger log = LoggerFactory.getLogger(StockKlineServiceImpl.class);

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private KlineDataSourceRouter dataSourceRouter;

    @Override
    public List<StockKline> getKline(String stockCode, String market, String klineType,
                                     String startDate, String endDate)
    {
        List<StockKline> existing = stockKlineMapper.selectByCodeTypeDateRange(stockCode, klineType, startDate, endDate);
        if (existing != null && !existing.isEmpty())
        {
            return existing;
        }

        try
        {
            List<StockKline> fetched = dataSourceRouter.fetchKline(stockCode, market, klineType, startDate, endDate);
            if (fetched != null && !fetched.isEmpty())
            {
                stockKlineMapper.insertIgnoreKlineBatch(fetched);
            }
            return stockKlineMapper.selectByCodeTypeDateRange(stockCode, klineType, startDate, endDate);
        }
        catch (Exception e)
        {
            log.error("获取K线数据失败: stockCode={}, klineType={}", stockCode, klineType, e);
            throw new ServiceException("获取K线数据失败: " + stockCode);
        }
    }

    @Override
    public void syncTodayKline(String stockCode, String market)
    {
        try
        {
            String today = java.time.LocalDate.now().toString();
            List<StockKline> list = dataSourceRouter.fetchKline(stockCode, market, "D", today, today);
            if (list != null && !list.isEmpty())
            {
                stockKlineMapper.insertIgnoreKlineBatch(list);
            }
        }
        catch (Exception e)
        {
            log.error("同步K线数据失败: stockCode={}", stockCode, e);
        }
    }
}
