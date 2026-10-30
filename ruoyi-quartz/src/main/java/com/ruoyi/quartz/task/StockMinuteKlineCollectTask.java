package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockMinuteKlineCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 涨停股分时K线落库任务
 *
 * 每个交易日 15:05 运行：把「最近 4 个交易日涨停过的股票」当日分时（m5）写入 stock_kline，
 * 每只涨停股会依次在 D、D+1、D+2、D+3 被采集，自动攒齐 4 天分时。
 *
 * 建议 cron: 0 5 15 ? * MON-FRI（收盘后、日K同步前均可）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockMinuteKlineCollectTask.execute()
 * 也支持带日期补采：stockMinuteKlineCollectTask.execute('2026-10-30')
 *
 * @author ruoyi
 */
@Component("stockMinuteKlineCollectTask")
public class StockMinuteKlineCollectTask
{
    private static final Logger log = LoggerFactory.getLogger(StockMinuteKlineCollectTask.class);

    @Autowired
    private IStockMinuteKlineCollectService stockMinuteKlineCollectService;

    public void execute()
    {
        execute(null);
    }

    public void execute(String params)
    {
        log.info("开始采集涨停股分时K线...");
        try
        {
            stockMinuteKlineCollectService.execute(params);
        }
        catch (Exception e)
        {
            log.error("涨停股分时K线落库失败", e);
            throw new RuntimeException("涨停股分时K线落库失败", e);
        }
    }
}
