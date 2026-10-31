package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.EastmoneyLockupDataSource;
import com.ruoyi.system.domain.stock.StockLockupSchedule;
import com.ruoyi.system.mapper.StockLockupScheduleMapper;
import com.ruoyi.system.service.IStockLockupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 股票限售解禁日程实现
 *
 * @author ruoyi
 */
@Service
public class StockLockupServiceImpl implements IStockLockupService
{
    private static final Logger log = LoggerFactory.getLogger(StockLockupServiceImpl.class);

    private static final int DB_BATCH_SIZE = 500;

    @Autowired
    private EastmoneyLockupDataSource eastmoneyLockupDataSource;

    @Autowired
    private StockLockupScheduleMapper stockLockupScheduleMapper;

    @Value("${stock.lockup.sync-days:120}")
    private int defaultSyncDays;

    @Override
    public void syncUpcoming(int days)
    {
        int syncDays = days > 0 ? days : defaultSyncDays;
        LocalDate start = LocalDate.now();
        LocalDate end = start.plusDays(syncDays);

        List<StockLockupSchedule> list = eastmoneyLockupDataSource.fetch(start, end);
        if (list.isEmpty())
        {
            log.warn("解禁数据为空，跳过写入: {} ~ {}", start, end);
            return;
        }

        int rows = 0;
        for (int i = 0; i < list.size(); i += DB_BATCH_SIZE)
        {
            List<StockLockupSchedule> batch = list.subList(i, Math.min(i + DB_BATCH_SIZE, list.size()));
            stockLockupScheduleMapper.batchUpsert(new ArrayList<>(batch));
            rows += batch.size();
        }
        log.info("解禁日程同步完成: {} ~ {}, 写入 {} 条", start, end, rows);
    }

    @Override
    public List<StockLockupSchedule> selectByFreeDateRange(LocalDate start, LocalDate end)
    {
        return stockLockupScheduleMapper.selectByFreeDateRange(
                java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    @Override
    public Set<String> selectRiskCodes(LocalDate start, LocalDate end, BigDecimal minRatio)
    {
        List<String> codes = stockLockupScheduleMapper.selectRiskCodes(
                java.sql.Date.valueOf(start), java.sql.Date.valueOf(end), minRatio);
        return codes == null ? new HashSet<>() : new HashSet<>(codes);
    }
}
