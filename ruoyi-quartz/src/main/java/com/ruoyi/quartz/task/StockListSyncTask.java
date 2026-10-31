package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockListSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 全市场股票列表/新股同步任务
 *
 * 建议 cron: 0 5 16 ? * FRI（每周五收盘后）
 * 调用目标: stockListSyncTask.sync()
 *
 * @author ruoyi
 */
@Component("stockListSyncTask")
public class StockListSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockListSyncTask.class);

    @Autowired
    private IStockListSyncService stockListSyncService;

    public void sync()
    {
        log.info("开始同步全市场股票列表...");
        try
        {
            stockListSyncService.syncAll();
        }
        catch (Exception e)
        {
            log.error("股票列表同步失败", e);
            throw new RuntimeException("股票列表同步失败", e);
        }
    }
}
