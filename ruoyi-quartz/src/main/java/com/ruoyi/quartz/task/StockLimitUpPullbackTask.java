package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockLimitUpPullbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 首二板回调尾盘推荐任务
 *
 * 每个交易日 14:45 之后运行：取「昨日首板/二板」作为候选，用当日分时 K 线判断回调承接质量
 * （回调幅度、分时收盘位置、是否站在分时均价线上、尾盘是否企稳），
 * 通过的票生成「首二板回调_日期」分组并邮件推送，供 15:00 前买入、次日卖出。
 *
 * 建议 cron: 0 46 14 ? * MON-FRI（与尾盘推荐错开 1 分钟，避免同时打腾讯接口）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockLimitUpPullbackTask.execute()
 *
 * @author ruoyi
 */
@Component("stockLimitUpPullbackTask")
public class StockLimitUpPullbackTask
{
    private static final Logger log = LoggerFactory.getLogger(StockLimitUpPullbackTask.class);

    @Autowired
    private IStockLimitUpPullbackService stockLimitUpPullbackService;

    public void execute()
    {
        execute(null);
    }

    /**
     * @param params 截止日期（yyyy-MM-dd），为空则默认当天
     */
    public void execute(String params)
    {
        log.info("开始执行首二板回调推荐...");
        try
        {
            stockLimitUpPullbackService.execute(params);
        }
        catch (Exception e)
        {
            log.error("首二板回调推荐执行失败", e);
            // 向上抛出，让 RuoYi 任务日志记录为失败
            throw new RuntimeException("首二板回调推荐执行失败", e);
        }
    }
}
