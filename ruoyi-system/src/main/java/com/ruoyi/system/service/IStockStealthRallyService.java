package com.ruoyi.system.service;

/**
 * 偷偷上涨选股 Service
 *
 * 算法：取最近 lookbackDays 个交易日日K线，逐只要求：
 *   1. 累计涨幅在 [minGain, maxGain] 区间（温和上涨，不暴涨）；
 *   2. 期间无涨停（最新涨停日期为空或早于回看起始日）；
 *   3. 缩量上涨（后半段均量 / 前半段均量 < shrinkRatio）；
 *   4. 低换手率（日均换手率 < maxTurnover）；
 *   5. 低振幅（日均振幅 < maxAmplitude）。
 * 命中股票生成一个新分组。
 */
public interface IStockStealthRallyService
{
    /**
     * 执行偷偷上涨选股，命中股票生成一个分组
     */
    void execute();
}
