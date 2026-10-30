package com.ruoyi.system.service;

/**
 * 首板/二板涨停后的「次日回调」尾盘推荐 Service
 *
 * 与「涨停首二板_日期」分组不同：那个分组只是把昨日首二板列出来供观察；
 * 本服务在次日 14:45 用分时 K 线判断回调质量（承接力度、分时均价、尾盘是否企稳），
 * 只推承接良好的票，供尾盘买入。
 */
public interface IStockLimitUpPullbackService
{
    /**
     * 执行首二板回调推荐
     *
     * @param date 截止日期（yyyy-MM-dd），为空则默认当天
     */
    void execute(String date);
}
