package com.ruoyi.system.service;

/**
 * 行业/概念资金流向推荐 Service
 *
 * 根据 stock_fund_flow 的资金流向快照，识别「走势向上（指数区间上涨+均线多头）且主力资金持续净流入」的行业/概念板块，
 * 并同步到 stock_plate 表：命中已存在板块则更新备注+置为重点关注(focus_flag=1)，
 * 不存在则新建板块并写入推荐备注。
 */
public interface IStockFundFlowRecommendService
{
    /**
     * 执行资金流向推荐
     */
    void execute();
}
