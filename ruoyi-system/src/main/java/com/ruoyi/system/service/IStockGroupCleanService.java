package com.ruoyi.system.service;

/**
 * 股票分组清理 Service
 *
 * @author ruoyi
 */
public interface IStockGroupCleanService
{
    /**
     * 删除创建超过 keepDays 天的日期分组（名称形如 xxx_yyyy-MM-dd）
     *
     * @param keepDays 保留天数
     * @return 删除的分组数量
     */
    int clean(int keepDays);
}
