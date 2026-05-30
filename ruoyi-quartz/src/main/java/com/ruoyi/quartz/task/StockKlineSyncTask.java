package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 股票K线数据定时同步任务
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockKlineSyncTask.syncKline()
 * 建议 cron: 0 30 15 * * ?（交易日下午3:30）
 *
 * @author ruoyi
 */
@Component("stockKlineSyncTask")
public class StockKlineSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockKlineSyncTask.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockKlineService stockKlineService;

    public void syncKline()
    {
        log.info("开始同步股票K线数据");
        Stock query = new Stock();
        List<Stock> stocks = stockMapper.selectStockList(query);
        if (stocks == null || stocks.isEmpty())
        {
            log.info("无股票需要同步");
            return;
        }
        int successCount = 0;
        int failCount = 0;
        for (Stock stock : stocks)
        {
            try
            {
                stockKlineService.syncTodayKline(stock.getStockCode(), stock.getMarket());
                successCount++;
            }
            catch (Exception e)
            {
                log.error("同步失败: stockCode={}", stock.getStockCode(), e);
                failCount++;
            }
        }
        log.info("K线同步完成: 成功={}, 失败={}", successCount, failCount);
    }

    public void syncKline(String params)
    {
        syncKline();
    }
}
