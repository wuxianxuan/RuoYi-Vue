package com.ruoyi.system.service;

/**
 * 龙头股七大战法推荐引擎 Service
 *
 * 从 focus_flag=1 的重点关注板块（行业+概念）中汇总成分股，按
 * 「龙头股必杀七大战法」逐一识别，每个战法生成一个分组。
 */
public interface IStockLeaderStrategyService
{
    /**
     * 执行龙头股七大战法选股，生成 7 个分组
     */
    void execute();
}
