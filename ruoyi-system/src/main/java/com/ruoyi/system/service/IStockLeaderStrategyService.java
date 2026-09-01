package com.ruoyi.system.service;

/**
 * 龙头股七大战法推荐引擎 Service
 *
 * 从财联社涨停记录中选取最近15天涨停、且所属行业/概念为重点关注板块（focus_flag=1）的股票，
 * 按「龙头股必杀七大战法」逐一识别，每个战法生成一个分组。
 */
public interface IStockLeaderStrategyService
{
    /**
     * 执行龙头股七大战法选股，生成 7 个分组
     */
    void execute();
}
