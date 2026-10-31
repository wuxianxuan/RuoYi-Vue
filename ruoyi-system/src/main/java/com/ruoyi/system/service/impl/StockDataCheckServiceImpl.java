package com.ruoyi.system.service.impl;

import com.ruoyi.system.service.IMailNotifyService;
import com.ruoyi.system.service.IStockDataCheckService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 股票数据完整性校验实现
 *
 * @author ruoyi
 */
@Service
public class StockDataCheckServiceImpl implements IStockDataCheckService
{
    private static final Logger log = LoggerFactory.getLogger(StockDataCheckServiceImpl.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IMailNotifyService mailNotifyService;

    @Value("${stock.data-check.quote-min:5000}")
    private int quoteMin;

    @Value("${stock.data-check.kline-min:5000}")
    private int klineMin;

    @Override
    public String check()
    {
        LocalDate today = LocalDate.now();
        java.sql.Date date = java.sql.Date.valueOf(today);

        Integer quoteCount = count("select count(distinct stock_code) from stock_daily_quote where trade_date = ?", date);
        Integer klineCount = count("select count(distinct stock_code) from stock_kline where kline_type = 'D' and date(trade_time) = ?", date);
        Integer limitUpCount = count("select count(*) from stock_limit_up_record where trade_date = ?", date);
        Integer lockupCount = count("select count(*) from stock_lockup_schedule where free_date >= ?", date);

        log.info("数据校验 {}: 行情 {} 只, 日K {} 只, 涨停 {} 条, 未来解禁 {} 条",
                today, quoteCount, klineCount, limitUpCount, lockupCount);

        List<String> problems = new ArrayList<>();
        if (quoteCount < quoteMin)
        {
            problems.add("行情快照不足: " + quoteCount + " < " + quoteMin);
        }
        if (klineCount < klineMin)
        {
            problems.add("日K不足: " + klineCount + " < " + klineMin);
        }
        if (limitUpCount == 0)
        {
            problems.add("当日涨停记录为 0（可能是非交易日或采集失败）");
        }
        if (lockupCount == 0)
        {
            problems.add("未来解禁日程为 0（解禁同步可能失败）");
        }

        if (problems.isEmpty())
        {
            return "";
        }
        String message = String.join("\n", problems);
        log.warn("数据校验未通过: {}", message.replace("\n", "; "));
        mailNotifyService.send("股票数据校验告警 " + today, message);
        return message;
    }

    private Integer count(String sql, Object arg)
    {
        try
        {
            Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
            return value == null ? 0 : value;
        }
        catch (Exception e)
        {
            log.error("数据校验查询失败: {}, error={}", sql, e.getMessage());
            return 0;
        }
    }
}
