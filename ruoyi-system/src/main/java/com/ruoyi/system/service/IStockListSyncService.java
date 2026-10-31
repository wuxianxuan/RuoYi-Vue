package com.ruoyi.system.service;

/**
 * 全市场股票列表同步 Service
 *
 * 基础页不再允许手工新增股票，新股依赖本任务从东财全量列表补齐。
 *
 * @author ruoyi
 */
public interface IStockListSyncService
{
    /**
     * 同步全市场股票代码/名称（已存在的只更新名称，不动行情快照）
     *
     * @return 本次接口返回的股票数量
     */
    int syncAll();
}
