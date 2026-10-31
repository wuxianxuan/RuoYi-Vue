package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.datasource.TencentQuoteDataSource;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockQuoteSnapshot;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockQuoteSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 每日行情快照同步实现
 *
 * 流程：全市场股票 → 腾讯批量快照 → 过滤非当日数据 → 批量 upsert stock / stock_daily_quote。
 * 停牌或无有效价格的股票不覆盖已有快照。
 *
 * @author ruoyi
 */
@Service
public class StockQuoteSyncServiceImpl implements IStockQuoteSyncService
{
    private static final Logger log = LoggerFactory.getLogger(StockQuoteSyncServiceImpl.class);

    private static final int DB_BATCH_SIZE = 200;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    @Autowired
    private TencentQuoteDataSource tencentQuoteDataSource;

    @Override
    public void syncDailyQuote()
    {
        LocalDate today = LocalDate.now();
        if (TradingDayUtils.isWeekend(today))
        {
            log.info("今日为周末，跳过行情同步");
            return;
        }

        long start = System.currentTimeMillis();
        List<Stock> stocks = stockMapper.selectStocksByMarkets(Arrays.asList("SH", "SZ", "BJ"));
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("股票表为空，跳过行情同步");
            return;
        }

        List<StockQuoteSnapshot> snapshots = tencentQuoteDataSource.fetchQuotes(stocks);
        if (snapshots.isEmpty())
        {
            log.warn("腾讯行情未返回有效数据，跳过本次写入");
            return;
        }

        // 只接受当日行情：节假日/数据未更新时接口会返回上一交易日，避免写错日期
        List<StockQuoteSnapshot> todaySnapshots = new ArrayList<>(snapshots.size());
        for (StockQuoteSnapshot snapshot : snapshots)
        {
            if (today.equals(snapshot.getTradeDate()))
            {
                todaySnapshots.add(snapshot);
            }
        }
        if (todaySnapshots.isEmpty())
        {
            log.warn("腾讯行情日期均非今日（{}），可能是非交易日或接口未更新，跳过写入", today);
            return;
        }

        List<Stock> stockUpdates = new ArrayList<>(todaySnapshots.size());
        List<StockDailyQuote> quoteUpdates = new ArrayList<>(todaySnapshots.size());
        for (StockQuoteSnapshot snapshot : todaySnapshots)
        {
            stockUpdates.add(toStock(snapshot));
            quoteUpdates.add(toDailyQuote(snapshot));
        }

        int stockRows = flushStocks(stockUpdates);
        int quoteRows = flushQuotes(quoteUpdates);

        log.info("每日行情同步完成: 请求 {} 只, 当日有效 {} 只, stock 写入 {} 行, stock_daily_quote 写入 {} 行, 耗时 {} ms",
                stocks.size(), todaySnapshots.size(), stockRows, quoteRows, System.currentTimeMillis() - start);
    }

    private Stock toStock(StockQuoteSnapshot snapshot)
    {
        Stock stock = new Stock();
        stock.setStockCode(snapshot.getStockCode());
        stock.setStockName(snapshot.getStockName());
        stock.setMarket(snapshot.getMarket());
        stock.setCurrentPrice(snapshot.getCurrentPrice());
        stock.setChangeRate(snapshot.getChangeRate());
        stock.setPriceChange(snapshot.getPriceChange());
        stock.setSpeed(snapshot.getSpeed());
        stock.setTurnoverRate(snapshot.getTurnoverRate());
        stock.setVolumeRatio(snapshot.getVolumeRatio());
        stock.setAmplitude(snapshot.getAmplitude());
        stock.setTurnoverAmount(snapshot.getTurnoverAmount());
        stock.setCirculatingShares(snapshot.getCirculatingShares());
        stock.setCircMarketCap(snapshot.getCircMarketCap());
        stock.setPeRatio(snapshot.getPeRatio());
        return stock;
    }

    private StockDailyQuote toDailyQuote(StockQuoteSnapshot snapshot)
    {
        StockDailyQuote quote = new StockDailyQuote();
        quote.setStockCode(snapshot.getStockCode());
        quote.setTradeDate(java.sql.Date.valueOf(snapshot.getTradeDate()));
        quote.setCurrentPrice(snapshot.getCurrentPrice());
        quote.setChangeRate(snapshot.getChangeRate());
        quote.setPriceChange(snapshot.getPriceChange());
        quote.setSpeed(snapshot.getSpeed());
        quote.setTurnoverRate(snapshot.getTurnoverRate());
        quote.setVolumeRatio(snapshot.getVolumeRatio());
        quote.setAmplitude(snapshot.getAmplitude());
        quote.setTurnoverAmount(snapshot.getTurnoverAmount());
        quote.setCirculatingShares(snapshot.getCirculatingShares());
        quote.setCircMarketCap(snapshot.getCircMarketCap());
        quote.setPeRatio(snapshot.getPeRatio());
        return quote;
    }

    private int flushStocks(List<Stock> stocks)
    {
        int rows = 0;
        for (int i = 0; i < stocks.size(); i += DB_BATCH_SIZE)
        {
            List<Stock> batch = stocks.subList(i, Math.min(i + DB_BATCH_SIZE, stocks.size()));
            stockMapper.batchInsertListIgnoreSame(new ArrayList<>(batch));
            rows += batch.size();
        }
        return rows;
    }

    private int flushQuotes(List<StockDailyQuote> quotes)
    {
        int rows = 0;
        for (int i = 0; i < quotes.size(); i += DB_BATCH_SIZE)
        {
            List<StockDailyQuote> batch = quotes.subList(i, Math.min(i + DB_BATCH_SIZE, quotes.size()));
            stockDailyQuoteMapper.batchUpsert(new ArrayList<>(batch));
            rows += batch.size();
        }
        return rows;
    }
}
