package com.ruoyi.system.service;

/**
 * 趋势积累池日推荐 Service
 *
 * 「趋势上涨选股」任务每日全量覆盖积累分组（含 ATR 波段删除），并把波段状态写入 stock_rising_state 表；
 * 本服务按波段状态把积累池拆成「上升」「震荡」两个日推荐分组（换手率达标才进），
 * 并遍历当日涨停记录过滤出首板/二板生成「涨停首二板_日期」分组。
 */
public interface IStockDayTradingService
{
    /**
     * 生成「上升」「震荡」两个日推荐分组 + 「涨停首二板」分组
     *
     * @param date 截止日期（yyyy-MM-dd），为空则默认当天
     */
    void execute(String date);
}
