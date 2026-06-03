package com.ruoyi.quartz.task;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Autowired
    @Qualifier("threadPoolTaskExecutor")
    private ThreadPoolTaskExecutor executor;

    /** 默认同步最近30天 */
    private static final int DEFAULT_DAYS = 30;

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
     * 执行K线同步（多线程并发）
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

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            ConcurrentLinkedQueue<String> failMessages = new ConcurrentLinkedQueue<>();

            List<CompletableFuture<Void>> futures = new ArrayList<>(stocks.size());
            for (Stock stock : stocks)
            {
                if (stock.getMarket() == null || stock.getStockCode() == null)
                {
                    log.warn("跳过无效股票数据: id={}", stock.getId());
                    continue;
                }
                String stockCode = stock.getStockCode();
                String market = stock.getMarket();
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try
                    {
                        stockKlineService.getKline(stockCode, market, klineType, startDate, endDate);
                        successCount.incrementAndGet();
                    }
                    catch (Exception e)
                    {
                        failCount.incrementAndGet();
                        String msg = String.format("同步失败: stockCode=%s, error=%s", stockCode, e.getMessage());
                        failMessages.add(msg);
                        log.error(msg, e);
                    }
                }, executor);
                futures.add(future);
            }

            // 等待所有任务完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 输出汇总日志
            log.info("K线同步完成: 总数={}, 成功={}, 失败={}", stocks.size(),
                    successCount.get(), failCount.get());
            if (!failMessages.isEmpty())
            {
                log.warn("同步失败的股票详情: {}", String.join("; ", failMessages));
            }
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
