package com.ruoyi.system.service.impl;

import com.ruoyi.system.datasource.EastmoneyStockListDataSource;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockListSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 全市场股票列表同步实现
 *
 * @author ruoyi
 */
@Service
public class StockListSyncServiceImpl implements IStockListSyncService
{
    private static final Logger log = LoggerFactory.getLogger(StockListSyncServiceImpl.class);

    private static final int DB_BATCH_SIZE = 500;

    @Autowired
    private EastmoneyStockListDataSource eastmoneyStockListDataSource;

    @Autowired
    private StockMapper stockMapper;

    @Override
    public int syncAll()
    {
        List<Stock> stocks = eastmoneyStockListDataSource.fetchAll();
        if (stocks.isEmpty())
        {
            log.warn("股票列表为空，跳过写入");
            return 0;
        }

        int rows = 0;
        for (int i = 0; i < stocks.size(); i += DB_BATCH_SIZE)
        {
            List<Stock> batch = stocks.subList(i, Math.min(i + DB_BATCH_SIZE, stocks.size()));
            stockMapper.batchInsertStockIdentity(new ArrayList<>(batch));
            rows += batch.size();
        }
        log.info("股票列表同步完成: 写入/更新 {} 条", rows);
        return stocks.size();
    }
}
