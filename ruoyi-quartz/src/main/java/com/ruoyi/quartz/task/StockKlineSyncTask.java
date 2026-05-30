package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.StockFavorite;
import com.ruoyi.system.mapper.StockFavoriteMapper;
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
    private StockFavoriteMapper stockFavoriteMapper;

    @Autowired
    private IStockKlineService stockKlineService;

    public void syncKline()
    {
        log.info("开始同步自选股K线数据");
        StockFavorite query = new StockFavorite();
        List<StockFavorite> favorites = stockFavoriteMapper.selectFavoriteList(query);
        if (favorites == null || favorites.isEmpty())
        {
            log.info("无自选股需要同步");
            return;
        }
        int successCount = 0;
        int failCount = 0;
        for (StockFavorite fav : favorites)
        {
            try
            {
                stockKlineService.syncTodayKline(fav.getStockCode(), fav.getMarket());
                successCount++;
            }
            catch (Exception e)
            {
                log.error("同步失败: stockCode={}", fav.getStockCode(), e);
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
