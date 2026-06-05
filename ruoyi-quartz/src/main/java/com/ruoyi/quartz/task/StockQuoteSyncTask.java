package com.ruoyi.quartz.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同花顺个股行情同步定时任务 (v3)
 *
 * 数据来源：stock-tools Node.js 脚本抓取的 JSON 文件 (../stock-tools/data/ths_quotes.json)
 *
 * 写入策略（拆分）:
 *   stock 表           → 仅基础信息（stock_code, stock_name, market），batchInsertListIgnoreSame 只插不覆盖已有
 *   stock_daily_quote  → 每日行情快照（价格/PE/市值/换手率等），INSERT IGNORE 保留全部历史
 *
 * @author ruoyi
 */
@Component("stockQuoteSyncTask")
public class StockQuoteSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockQuoteSyncTask.class);

    private static final int DB_BATCH_SIZE = 200;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    @Value("${stock.ths.quote.data-file:../stock-tools/data/ths_quotes.json}")
    private String dataFilePath;

    // ==================== 入口 ====================

    public void sync() { sync(null); }

    public void sync(String params)
    {
        log.info("========== 开始同步同花顺个股行情 ==========");
        log.info("数据文件: {}", dataFilePath);

        try
        {
            File file = new File(dataFilePath);
            if (!file.exists())
            {
                log.error("数据文件不存在: {}", file.getAbsolutePath());
                log.error("请先运行: cd ../stock-tools && npm run quote");
                return;
            }

            // 1. 读取 JSON
            log.info("[1/4] 读取 JSON...");
            JsonNode root = objectMapper.readTree(file);
            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isArray() || dataNode.size() == 0)
            {
                log.warn("JSON 文件中无数据");
                return;
            }

            // 从 fetchTime 提取交易日期
            String fetchTime = root.has("fetchTime") ? root.get("fetchTime").asText() : "";
            LocalDate tradeDate = parseTradeDate(fetchTime);
            log.info("交易日期: {}, 总条数: {}", tradeDate, dataNode.size());

            // 2. 转换并分两路写入
            log.info("[2/4] 转换数据...");
            List<Stock> stockBatch = new ArrayList<>();
            List<StockDailyQuote> quoteBatch = new ArrayList<>();
            int total = 0;

            for (JsonNode item : dataNode)
            {
                Stock stock = buildStock(item);
                StockDailyQuote quote = buildDailyQuote(item, tradeDate);

                if (stock.getStockCode() == null)
                {
                    log.trace("跳过无效数据: {}", item);
                    continue;
                }

                stockBatch.add(stock);
                quoteBatch.add(quote);
                total++;

                if (stockBatch.size() >= DB_BATCH_SIZE)
                {
                    flushStockBatch(stockBatch);
                    flushQuoteBatch(quoteBatch);
                }
            }
            if (!stockBatch.isEmpty())
            {
                flushStockBatch(stockBatch);
                flushQuoteBatch(quoteBatch);
            }

            // 3. 日志
            log.info("[3/4] stock 基础信息已更新");
            log.info("[4/4] stock_daily_quote 快照已保存 (date={})", tradeDate);
            log.info("========== 完成: {} 条 ==========", total);
        }
        catch (Exception e)
        {
            log.error("同步失败: {}", e.getMessage(), e);
        }
    }

    // ==================== 数据构建 ====================

    /** 构建完整 Stock（stock 表 — 向后兼容，保留完整快照字段） */
    private Stock buildStock(JsonNode item)
    {
        Stock stock = new Stock();
        LocalDateTime now = LocalDateTime.now();
        stock.setStockCode(getString(item, "code", "stockCode", "stock_code", "symbol"));
        stock.setStockName(getString(item, "name", "stockName", "stock_name"));
        stock.setMarket(inferMarket(item));
        // 快照字段（保留在 stock 表中供当前页面展示）
        stock.setCurrentPrice(getDecimal(item, "now", "current_price", "price", "last_price", "trade"));
        stock.setChangeRate(getDecimal(item, "change", "change_rate", "zdf", "changepercent", "pctChg"));
        stock.setPriceChange(getDecimal(item, "change_amount", "price_change", "zde", "changeamount", "chg"));
        stock.setTurnoverRate(getDecimal(item, "turnover", "turnover_rate", "turnoverrate", "hsl", "turn"));
        stock.setVolumeRatio(getDecimal(item, "vol_ratio", "volume_ratio", "volratio", "lb"));
        stock.setAmplitude(getDecimal(item, "amplitude", "zf", "amplitude_rate"));
        stock.setTurnoverAmount(getDecimal(item, "amount", "turnover_amount", "cje", "cjl"));
        stock.setCirculatingShares(getDecimal(item, "float_share", "circulating_shares", "ltg"));
        stock.setPeRatio(getDecimal(item, "pe", "pe_ratio", "peRatio", "per"));
        stock.setTotalMarketCap(getDecimal(item, "total_mv", "total_market_cap", "total_market_value"));
        stock.setCircMarketCap(getDecimal(item, "circ_mv", "circ_market_cap", "circ_market_value"));
        stock.setCreateBy("ths-sync");
        stock.setUpdateBy("ths-sync");
        stock.setUpdateTime(java.sql.Timestamp.valueOf(now));
        return stock;
    }

    /** 构建每日行情快照（stock_daily_quote 表） */
    private StockDailyQuote buildDailyQuote(JsonNode item, LocalDate tradeDate)
    {
        StockDailyQuote q = new StockDailyQuote();
        q.setStockCode(getString(item, "code", "stockCode", "stock_code", "symbol"));
        q.setTradeDate(Date.valueOf(tradeDate));
        q.setCurrentPrice(getDecimal(item, "now", "current_price", "price", "last_price", "trade"));
        q.setChangeRate(getDecimal(item, "change", "change_rate", "zdf", "changepercent", "pctChg"));
        q.setPriceChange(getDecimal(item, "change_amount", "price_change", "zde", "changeamount", "chg"));
        q.setSpeed(getDecimal(item, "speed"));
        q.setTurnoverRate(getDecimal(item, "turnover", "turnover_rate", "turnoverrate", "hsl", "turn"));
        q.setVolumeRatio(getDecimal(item, "vol_ratio", "volume_ratio", "volratio", "lb"));
        q.setAmplitude(getDecimal(item, "amplitude", "zf", "amplitude_rate"));
        q.setTurnoverAmount(getDecimal(item, "amount", "turnover_amount", "cje", "cjl"));
        q.setCirculatingShares(getDecimal(item, "float_share", "circulating_shares", "ltg"));
        q.setPeRatio(getDecimal(item, "pe", "pe_ratio", "peRatio", "per"));
        q.setTotalMarketCap(getDecimal(item, "total_mv", "total_market_cap", "total_market_value"));
        q.setCircMarketCap(getDecimal(item, "circ_mv", "circ_market_cap", "circ_market_value"));
        return q;
    }

    private String inferMarket(JsonNode item)
    {
        String market = getString(item, "market", "m", "type");
        if (market != null && !market.isEmpty()) return market.toUpperCase();
        String code = getString(item, "code", "stockCode", "stock_code", "symbol");
        if (code == null || code.length() < 6) return null;
        char first = code.charAt(0);
        if (first == '6' || first == '9') return "SH";
        if (first == '0' || first == '2' || first == '3') return "SZ";
        if (first == '8' || first == '4') return "BJ";
        return null;
    }

    /** 从 ISO 时间字符串提取日期 */
    private LocalDate parseTradeDate(String fetchTime)
    {
        try
        {
            if (fetchTime != null && fetchTime.length() >= 10)
            {
                return LocalDate.parse(fetchTime.substring(0, 10));
            }
        }
        catch (Exception ignored) { }
        return LocalDate.now();
    }

    // ==================== 工具方法 ====================

    private String getString(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull()) return field.asText();
        }
        return null;
    }

    private BigDecimal getDecimal(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull())
            {
                try
                {
                    String text = field.asText();
                    if (text != null && !text.isEmpty() && !"-".equals(text) && !"--".equals(text))
                    {
                        return new BigDecimal(text);
                    }
                }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    private void flushStockBatch(List<Stock> batch)
    {
        if (batch.isEmpty()) return;
        try
        {
            stockMapper.batchInsertListIgnoreSame(batch);
            log.debug("stock 写入: {} 条", batch.size());
            batch.clear();
        }
        catch (Exception e)
        {
            log.error("stock 写入失败 ({} 条): {}", batch.size(), e.getMessage());
            if (batch.size() > DB_BATCH_SIZE * 3) batch.clear();
        }
    }

    private void flushQuoteBatch(List<StockDailyQuote> batch)
    {
        if (batch.isEmpty()) return;
        try
        {
            stockDailyQuoteMapper.batchInsertIgnore(batch);
            log.debug("stock_daily_quote 写入: {} 条", batch.size());
            batch.clear();
        }
        catch (Exception e)
        {
            log.error("stock_daily_quote 写入失败 ({} 条): {}", batch.size(), e.getMessage());
            if (batch.size() > DB_BATCH_SIZE * 3) batch.clear();
        }
    }
}
