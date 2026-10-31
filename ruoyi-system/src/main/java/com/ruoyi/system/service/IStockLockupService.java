package com.ruoyi.system.service;

import com.ruoyi.system.domain.stock.StockLockupSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * 股票限售解禁日程 Service
 *
 * @author ruoyi
 */
public interface IStockLockupService
{
    /**
     * 同步未来 N 天的解禁日程
     *
     * @param days 天数，<=0 时使用配置默认值
     */
    void syncUpcoming(int days);

    /**
     * 查询指定日期区间的解禁日程
     */
    List<StockLockupSchedule> selectByFreeDateRange(LocalDate start, LocalDate end);

    /**
     * 查询存在大比例解禁风险的股票代码（未来 N 天内解禁比例 >= 阈值）
     *
     * @param start    区间开始
     * @param end      区间结束
     * @param minRatio 解禁比例下限（0~1）
     */
    Set<String> selectRiskCodes(LocalDate start, LocalDate end, BigDecimal minRatio);
}
