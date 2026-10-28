package com.ruoyi.quartz.task;

import com.ruoyi.system.service.IStockTailRecommendEvalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 尾盘推荐次日表现回填任务
 *
 * 建议 cron: 0 10 16 ? * MON-FRI（每个交易日下午16:10，依赖日K同步 stockKlineSyncTask 已完成）。
 * 把前一天 14:45 推荐的股票在当天的开高低收回填到 stock_tail_recommend_record，
 * 并打印最近样本的滚动统计（次日平均涨幅/上涨占比/平均最大浮亏），
 * 用于验证「低涨幅 + 低乖离」口径是否真的降低了次日回调。
 *
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockTailRecommendEvalTask.execute()
 * 漏跑/重跑安全：只处理 eval_status 为空的记录，次日K线没同步时保持待回填，下次自动补上。
 *
 * @author ruoyi
 */
@Component("stockTailRecommendEvalTask")
public class StockTailRecommendEvalTask
{
    private static final Logger log = LoggerFactory.getLogger(StockTailRecommendEvalTask.class);

    @Autowired
    private IStockTailRecommendEvalService stockTailRecommendEvalService;

    public void execute()
    {
        log.info("开始回填尾盘推荐次日表现...");
        try
        {
            stockTailRecommendEvalService.execute();
        }
        catch (Exception e)
        {
            log.error("尾盘推荐次日表现回填失败", e);
            // 向上抛出，让 RuoYi 任务日志记录为失败，避免"静默成功"掩盖数据缺口
            throw new RuntimeException("尾盘推荐次日表现回填失败", e);
        }
    }
}
