package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.datasource.TencentKlineDataSource;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.service.IStockKlineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Semaphore;

@Service
public class StockKlineServiceImpl implements IStockKlineService
{
    private static final Logger log = LoggerFactory.getLogger(StockKlineServiceImpl.class);

    /**
     * 最大并发写库线程数。
     * ON DUPLICATE KEY UPDATE 在 REPEATABLE READ 下对二级唯一索引 uk_code_type_time 做重复检测时
     * 会先加共享锁、再加插入意向锁，多个写线程即使写入不同 stock_code 也会因间隙锁互相等待形成死锁。
     * 并发写只能“降低概率”而非“根除”，故串行化写库（=1）彻底消除死锁；拉取网络数据仍在信号量外并行，性能影响可忽略。
     */
    private static final int MAX_CONCURRENT_WRITES = 1;

    /** 死锁重试次数（共 MAX_RETRY + 1 次尝试） */
    private static final int MAX_RETRY = 3;

    /** 重试基础退避时间（毫秒） */
    private static final long RETRY_BASE_DELAY_MS = 200L;

    /** 全局写库信号量：同步任务多线程并发写 stock_kline 时串行化数据库写入 */
    private static final Semaphore WRITE_SEMAPHORE = new Semaphore(MAX_CONCURRENT_WRITES);

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private TencentKlineDataSource tencentKlineDataSource;

    /**
     * 页面查询：直接读本地库，不再调外部数据源
     */
    @Override
    public List<StockKline> getKline(String stockCode, String klineType, String startDate, String endDate)
    {
        return stockKlineMapper.selectByCodeTypeDateRange(stockCode, klineType, startDate, endDate);
    }

    /**
     * 定时同步：从腾讯源拉取并落库，靠唯一键 ON DUPLICATE KEY UPDATE 覆盖更新
     */
    @Override
    public void syncKline(String stockCode, String market, String klineType, String startDate, String endDate)
    {
        List<StockKline> fetched = tencentKlineDataSource.fetchKline(stockCode, market, klineType, startDate, endDate);
        if (fetched == null || fetched.isEmpty())
        {
            return;
        }

        // 同步任务多线程并发写 stock_kline，ON DUPLICATE KEY UPDATE 在唯一键 uk_code_type_time 上检测冲突
        // 会加共享锁，与并发插入的意向锁冲突形成死锁。这里通过信号量限制并发写库，并对残余死锁做重试。
        for (int attempt = 0; ; attempt++)
        {
            try
            {
                WRITE_SEMAPHORE.acquire();
                try
                {
                    stockKlineMapper.upsertKlineBatch(fetched);
                    return;
                }
                finally
                {
                    WRITE_SEMAPHORE.release();
                }
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception e)
            {
                if (!isDeadlock(e) || attempt >= MAX_RETRY)
                {
                    throw e;
                }
                log.warn("K线批量入库死锁，第{}次重试: stockCode={}", attempt + 1, stockCode);
                sleepBeforeRetry(attempt);
            }
        }
    }

    /**
     * 判断异常是否为 MySQL 死锁（SQLState 40001 / 错误码 1213 / 消息含 Deadlock），
     * 兼容 MyBatis-Spring 包装后的异常，沿 cause 链查找。
     */
    private boolean isDeadlock(Throwable t)
    {
        Throwable cur = t;
        while (cur != null)
        {
            if (cur instanceof SQLException)
            {
                SQLException se = (SQLException) cur;
                if ("40001".equals(se.getSQLState()) || se.getErrorCode() == 1213)
                {
                    return true;
                }
            }
            if (cur.getMessage() != null && cur.getMessage().contains("Deadlock"))
            {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 重试前退避，指数递增避免风暴
     */
    private void sleepBeforeRetry(int attempt)
    {
        try
        {
            Thread.sleep(RETRY_BASE_DELAY_MS * (attempt + 1));
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
