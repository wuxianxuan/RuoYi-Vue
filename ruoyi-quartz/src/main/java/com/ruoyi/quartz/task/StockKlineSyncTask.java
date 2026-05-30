package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 股票K线数据定时同步任务
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockKlineSyncTask.syncKline()
 * 建议 cron: 0 30 15 * * ?（交易日下午3:30）
 *
 * @author ruoyi
 */
@Component("stockKlineSyncTask")
public class StockKlineSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockKlineSyncTask.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private IStockKlineService stockKlineService;

    /** 默认同步最近30天 */
    private static final int DEFAULT_DAYS = 30;
    /** 腾讯接口限流间隔（毫秒） */
    private static final long API_INTERVAL_MS = 500;

    /**
     * 无参调用：同步最近30天K线数据
     */
    public void syncKline()
    {
        syncKline(DEFAULT_DAYS);
    }

    /**
     * 带参调用：按指定天数同步K线数据
     *
     * @param params 天数，如 "7" 表示同步最近7天
     */
    public void syncKline(String params)
    {
        int days = parseDays(params);
        syncKline(days);
    }

    /**
     * 执行K线同步
     *
     * @param days 同步最近多少天的数据
     */
    private void syncKline(int days)
    {
        String endDate = java.time.LocalDate.now().toString();
        String startDate = java.time.LocalDate.now().minusDays(days).toString();
        String klineType = "D";

        log.info("开始同步股票K线数据: 日期范围={} ~ {}, K线类型={}", startDate, endDate, klineType);
        try
        {
            List<Stock> stocks = stockMapper.selectStocksByMarkets(java.util.Arrays.asList("SH", "SZ"));
            if (stocks == null || stocks.isEmpty())
            {
                log.info("无A股需要同步");
                return;
            }
            log.info("待同步股票数量: {}", stocks.size());
            int successCount = 0;
            int failCount = 0;
            for (Stock stock : stocks)
            {
                if (stock.getMarket() == null || stock.getStockCode() == null)
                {
                    log.warn("跳过无效股票数据: id={}", stock.getId());
                    continue;
                }
                try
                {
                    Thread.sleep(API_INTERVAL_MS);
                    stockKlineService.getKline(stock.getStockCode(), stock.getMarket(), klineType, startDate, endDate);
                    successCount++;
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                    log.warn("同步被中断，已处理 {} 只股票", successCount);
                    break;
                }
                catch (Exception e)
                {
                    log.error("同步失败: stockCode={}", stock.getStockCode(), e);
                    failCount++;
                }
            }
            log.info("K线同步完成: 总数={}, 成功={}, 失败={}", stocks.size(), successCount, failCount);
        }
        catch (Exception e)
        {
            log.error("K线同步任务执行异常", e);
        }
    }

    /**
     * 解析参数字符串为天数，解析失败返回默认值
     */
    private int parseDays(String params)
    {
        if (params != null && !params.trim().isEmpty())
        {
            try
            {
                int days = Integer.parseInt(params.trim());
                if (days > 0)
                {
                    return days;
                }
                log.warn("天数必须为正数: {}, 使用默认{}天", days, DEFAULT_DAYS);
            }
            catch (NumberFormatException e)
            {
                log.warn("参数解析失败: {}, 使用默认{}天", params, DEFAULT_DAYS);
            }
        }
        return DEFAULT_DAYS;
    }
}
