package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockKline;

import java.util.List;

/**
 * 趋势上涨选股 Service（波段状态机算法）
 *
 * 对每只股票按日K线走波段状态机（进池 → 上升/震荡/回调 → 删除）：
 * 进池需近 entryWindow 日无涨停、区间涨幅达标、创窗口新高、收盘 > MA(maPeriod)；
 * 入池后按 ATR 归一化回撤判定震荡/回调/删除。
 * 每日全量重算，命中结果全量覆盖固定分组，并写入 stock_rising_state 表。
 */
public interface IStockRisingTrendService
{
    /**
     * 执行趋势上涨选股，命中结果全量覆盖固定分组
     *
     * @param date 截止日期（yyyy-MM-dd），为空则使用当天
     */
    void execute(String date);

    /**
     * 判定一组日K线在末根K线时是否处于上升趋势（在池内）
     *
     * @param klines 某只股票的日K线（无序亦可，内部按交易日升序排序）
     * @return true 表示趋势向上且在池内，false 表示不在池内或数据不足
     */
    boolean isRisingTrend(List<StockKline> klines);
}
