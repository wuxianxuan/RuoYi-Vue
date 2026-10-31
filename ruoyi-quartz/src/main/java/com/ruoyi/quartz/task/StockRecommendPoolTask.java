package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRecommendPoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 次日推荐池生成任务（119）
 *
 * 数据日收盘后运行：从趋势上升池 + 当日涨停首二板中筛选、评分，写入 stock_recommend_pool，
 * 并剔除未来 20 天内解禁比例 >= 5% 的候选。117 次日 14:45 读取该池做盘中过滤。
 *
 * 建议 cron: 0 0 17 ? * MON-FRI
 * 调用目标: stockRecommendPoolTask.execute()
 *
 * @author ruoyi
 */
@Component("stockRecommendPoolTask")
public class StockRecommendPoolTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendPoolTask.class);

    @Autowired
    private IStockRecommendPoolService stockRecommendPoolService;

    public void execute()
    {
        execute(null);
    }

    /**
     * @param params 数据截止日期（yyyy-MM-dd），为空默认当天
     */
    public void execute(String params)
    {
        LocalDate date = LocalDate.now();
        if (params != null && !params.trim().isEmpty())
        {
            try
            {
                date = LocalDate.parse(params.trim());
            }
            catch (Exception e)
            {
                log.warn("推荐池日期参数解析失败: {}, 使用今天", params);
            }
        }

        log.info("开始生成次日推荐池（数据日 {}）...", date);
        try
        {
            stockRecommendPoolService.buildPool(date);
        }
        catch (Exception e)
        {
            log.error("次日推荐池生成失败", e);
            throw new RuntimeException("次日推荐池生成失败", e);
        }
    }
}
