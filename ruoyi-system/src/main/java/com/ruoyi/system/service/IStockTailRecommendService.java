package com.ruoyi.system.service;

/**
 * 尾盘推荐 Service
 *
 * 每个交易日下午 14:45 运行（收盘前），基于「昨日分组（上升/震荡/涨停首二板）」的股票，
 * 拉取当日分时（分钟）K线，按「尾盘可买 + 不追高」口径筛选：
 * 先排除已涨停、涨幅过高、高开、近5日涨透、偏离MA5过远、量比/换手过热与流动性不足的股票，
 * 再要求当日上涨、收盘贴近阶段高点、尾盘不跳水，最后按综合评分取前 N 只，
 * 生成「尾盘推荐_日期」分组并邮件推送。
 */
public interface IStockTailRecommendService
{
    /**
     * 执行尾盘推荐
     *
     * @param date 截止日期（yyyy-MM-dd），为空则默认当天
     */
    void execute(String date);
}
