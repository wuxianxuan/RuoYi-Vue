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
     * 检查已有数据的时间范围是否覆盖入参要求的范围
     *
     * @param existing  数据库已有数据
     * @param startDate 请求开始日期
     * @param endDate   请求结束日期
     * @return true=已覆盖，false=未覆盖需要从外部数据源拉取
     */
    private boolean isDateRangeCovered(List<StockKline> existing, String startDate, String endDate)
    {
        if (existing == null || existing.isEmpty())
        {
            return false;
        }
        // 按tradeTime升序排列后取首尾
        java.util.Date minDate = existing.stream()
                .map(StockKline::getTradeTime)
                .filter(d -> d != null)
                .min(java.util.Date::compareTo)
                .orElse(null);
        java.util.Date maxDate = existing.stream()
                .map(StockKline::getTradeTime)
                .filter(d -> d != null)
                .max(java.util.Date::compareTo)
                .orElse(null);
        if (minDate == null || maxDate == null)
        {
            return false;
        }
        try
        {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
            java.util.Date reqStart = sdf.parse(startDate);
            java.util.Date reqEnd = sdf.parse(endDate);
            // 已有数据的范围必须完全覆盖请求的范围
            return !minDate.after(reqStart) && !maxDate.before(reqEnd);
        }
        catch (java.text.ParseException e)
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
