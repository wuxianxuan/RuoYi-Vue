package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockGroupCleanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 历史日期分组清理任务
 *
 * 只删除名称以 _yyyy-MM-dd 结尾且创建超过保留天数的分组，固定分组（如「首二板回调」）不动。
 *
 * 建议 cron: 0 0 18 ? * FRI
 * 调用目标: stockGroupCleanTask.clean()
 *
 * @author ruoyi
 */
@Component("stockGroupCleanTask")
public class StockGroupCleanTask
{
    private static final Logger log = LoggerFactory.getLogger(StockGroupCleanTask.class);

    @Autowired
    private IStockGroupCleanService stockGroupCleanService;

    @Value("${stock.group-clean.keep-days:30}")
    private int defaultKeepDays;

    public void clean()
    {
        clean(null);
    }

    /**
     * @param params 保留天数，为空使用配置
     */
    public void clean(String params)
    {
        int days = defaultKeepDays;
        if (params != null && !params.trim().isEmpty())
        {
            try
            {
                days = Integer.parseInt(params.trim());
            }
            catch (NumberFormatException e)
            {
                log.warn("分组保留天数解析失败: {}, 使用默认 {}", params, defaultKeepDays);
            }
        }
        log.info("开始清理超过 {} 天的日期分组...", days);
        try
        {
            stockGroupCleanService.clean(days);
        }
        catch (Exception e)
        {
            log.error("日期分组清理失败", e);
            throw new RuntimeException("日期分组清理失败", e);
        }
    }
}
