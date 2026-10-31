package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockDataCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 股票数据完整性校验任务
 *
 * 建议 cron: 0 20 17 ? * MON-FRI（在行情/K线/涨停/解禁任务之后）
 * 调用目标: stockDataCheckTask.check()
 *
 * @author ruoyi
 */
@Component("stockDataCheckTask")
public class StockDataCheckTask
{
    private static final Logger log = LoggerFactory.getLogger(StockDataCheckTask.class);

    @Autowired
    private IStockDataCheckService stockDataCheckService;

    public void check()
    {
        log.info("开始股票数据完整性校验...");
        try
        {
            String problems = stockDataCheckService.check();
            if (!problems.isEmpty())
            {
                // 邮件已在 Service 内发送；这里上抛让 RuoYi 任务日志标红
                throw new IllegalStateException("数据校验未通过: " + problems.replace("\n", "; "));
            }
        }
        catch (IllegalStateException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("数据完整性校验失败", e);
            throw new RuntimeException("数据完整性校验失败", e);
        }
    }
}
