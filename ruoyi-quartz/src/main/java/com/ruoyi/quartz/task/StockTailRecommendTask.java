package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockTailRecommendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 尾盘推荐定时任务
 *
 * 每个交易日下午 14:45 运行（收盘前 15 分钟），基于昨日分组（上升/震荡/涨停首二板）的股票，
 * 拉取当日分时K线，按「尾盘可买 + 不追高」口径筛选：先排除已涨停、涨幅过高、高开、近5日涨透、
 * 偏离MA5过远、量比/换手过热与流动性不足的股票，再要求上涨 + 收盘贴近高点 + 尾盘不跳水，
 * 最后按综合评分取前 N 只，生成「尾盘推荐_日期」分组，供用户在 15:00 收盘前买入。
 * 建议 cron: 0 45 14 ? * MON-FRI（每个交易日下午14:45，依赖分时数据实时抓取）。
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockTailRecommendTask.execute()
 */
@Component("stockTailRecommendTask")
public class StockTailRecommendTask
{
    private static final Logger log = LoggerFactory.getLogger(StockTailRecommendTask.class);

    @Autowired
    private IStockTailRecommendService stockTailRecommendService;

    /**
     * 执行尾盘推荐（无入参日期，默认当天）
     */
    public void execute()
    {
        execute(null);
    }

    /**
     * 带参入口：入参为截止日期（yyyy-MM-dd），为空则默认当天
     *
     * @param params 截止日期（yyyy-MM-dd）
     */
    public void execute(String params)
    {
        log.info("开始执行尾盘推荐...");
        try
        {
            stockTailRecommendService.execute(params);
        }
        catch (Exception e)
        {
            log.error("尾盘推荐执行失败", e);
            // 向上抛出，让 RuoYi 任务日志记录为失败；否则实验期"没数据"会被当成"没命中"
            throw new RuntimeException("尾盘推荐执行失败", e);
        }
    }
}
