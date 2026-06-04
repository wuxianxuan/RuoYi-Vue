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
        if (existing != null && !existing.isEmpty() && isDateRangeCovered(existing, startDate, endDate))
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

    /**
     * 检查已有数据是否完整覆盖入参要求的日期范围（逐日校验，避免中间缺失）
     *
     * @param existing  数据库已有数据
     * @param startDate 请求开始日期 (yyyy-MM-dd)
     * @param endDate   请求结束日期 (yyyy-MM-dd)
     * @return true=已完整覆盖，false=存在缺失需要从外部数据源拉取
     */
    private boolean isDateRangeCovered(List<StockKline> existing, String startDate, String endDate)
    {
        if (existing == null || existing.isEmpty())
        {
            return false;
        }
        try
        {
            java.time.LocalDate start = java.time.LocalDate.parse(startDate);
            java.time.LocalDate end = java.time.LocalDate.parse(endDate);

            // 收集已有数据的所有日期
            java.util.Set<java.time.LocalDate> existingDates = existing.stream()
                    .map(StockKline::getTradeTime)
                    .filter(d -> d != null)
                    .map(d -> d.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate())
                    .collect(java.util.stream.Collectors.toSet());

            // 逐日检查是否完整覆盖
            for (java.time.LocalDate d = start; !d.isAfter(end); d = d.plusDays(1))
            {
                if (!existingDates.contains(d))
                {
                    return false;
                }
            }
            return true;
        }
        catch (Exception e)
        {
            log.warn("日期解析失败: startDate={}, endDate={}", startDate, endDate);
            return false;
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
