package com.ruoyi.system.service;

/**
 * 股票数据完整性校验 Service
 *
 * @author ruoyi
 */
public interface IStockDataCheckService
{
    /**
     * 校验当日行情/K线/涨停/解禁数据是否齐全，异常时发邮件告警
     *
     * @return 问题描述，空字符串表示通过
     */
    String check();
}
