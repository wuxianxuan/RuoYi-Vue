package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockLockupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 股票限售解禁日程同步任务
 *
 * 数据来源：东方财富 RPT_LIFT_STAGE，默认同步未来 120 天。
 * 用途：119 推荐池按「未来 20 天内解禁比例 >= 5%」做风险过滤。
 *
 * 建议 cron: 0 20 16 ? * MON-FRI
 * 调用目标: stockLockupSyncTask.sync()
 *
 * @author ruoyi
 */
@Component("stockLockupSyncTask")
public class StockLockupSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockLockupSyncTask.class);

    @Autowired
    private IStockLockupService stockLockupService;

    public void sync()
    {
        sync(null);
    }

    /**
     * @param params 同步天数，为空使用配置 stock.lockup.sync-days
     */
    public void sync(String params)
    {
        int days = 0;
        if (params != null && !params.trim().isEmpty())
        {
            try
            {
                days = Integer.parseInt(params.trim());
            }
            catch (NumberFormatException e)
            {
                log.warn("解禁同步天数解析失败: {}, 使用默认配置", params);
            }
        }

        log.info("开始同步股票解禁日程...");
        try
        {
            stockLockupService.syncUpcoming(days);
        }
        catch (Exception e)
        {
            log.error("股票解禁日程同步失败", e);
            throw new RuntimeException("股票解禁日程同步失败", e);
        }
    }
}
