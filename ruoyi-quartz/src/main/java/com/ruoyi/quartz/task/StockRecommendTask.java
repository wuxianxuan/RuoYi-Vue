package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 股票推荐定时任务
 *
 * 建议执行时间：每日16:00（收盘后），依赖 K线同步 + 指标计算 + 涨停分析任务已完成
 */
@Component("stockRecommendTask")
public class StockRecommendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockRecommendTask.class);

    @Autowired
    private IStockRecommendService stockRecommendService;

    /** 默认推荐 Top N */
    private static final int DEFAULT_TOP_N = 20;

    /**
     * 无参入口（默认推荐 Top 20）
     */
    public void recommend()
    {
        recommend(String.valueOf(DEFAULT_TOP_N));
    }

    /**
     * 带参入口
     *
     * @param params 参数：推荐数量（正整数），如 "10"、"30"
     */
    public void recommend(String params)
    {
        int topN;
        try
        {
            topN = Integer.parseInt(params.trim());
            if (topN <= 0)
            {
                log.warn("推荐数量参数无效: {}, 使用默认值 {}", params, DEFAULT_TOP_N);
                topN = DEFAULT_TOP_N;
            }
            if (topN > 100)
            {
                log.warn("推荐数量过大: {}, 限制为100", topN);
                topN = 100;
            }
        }
        catch (NumberFormatException e)
        {
            log.warn("推荐数量参数解析失败: '{}', 使用默认值 {}", params, DEFAULT_TOP_N);
            topN = DEFAULT_TOP_N;
        }

        String today = LocalDate.now().toString();
        log.info("开始执行股票推荐: date={}, topN={}", today, topN);

        try
        {
            int count = stockRecommendService.executeRecommend(today, topN);
            log.info("股票推荐完成: date={}, 推荐数量={}", today, count);
        }
        catch (Exception e)
        {
            log.error("股票推荐执行失败", e);
        }
    }
}
