package com.ruoyi.quartz.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.mapper.StockMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 同花顺个股行情同步定时任务 (v2)
 *
 * 数据来源：Node.js Playwright 脚本抓取的 JSON 文件 (scripts/data/ths_quotes.json)
 * 本任务负责读取 JSON → 转换 → 批量更新 stock 表
 *
 * 使用方式：
 *   1. 手动运行抓取: cd scripts && set THS_USERNAME=xxx && set THS_PASSWORD=xxx && node ths-quote-fetcher.js
 *   2. 定时任务调用: stockQuoteSyncTask.sync()
 *
 * @author ruoyi
 */
@Component("stockQuoteSyncTask")
public class StockQuoteSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockQuoteSyncTask.class);

    /** 批量写入数据库的批次大小 */
    private static final int DB_BATCH_SIZE = 200;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private StockMapper stockMapper;

    /** JSON 数据文件路径 */
    @Value("${stock.ths.quote.data-file:scripts/data/ths_quotes.json}")
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
                log.error("请先运行抓取脚本: cd scripts && node ths-quote-fetcher.js");
                return;
            }

            // 1. 读取 JSON 文件
            log.info("[1/3] 读取 JSON 文件...");
            JsonNode root = objectMapper.readTree(file);
            JsonNode dataNode = root.get("data");

            if (dataNode == null || !dataNode.isArray() || dataNode.size() == 0)
            {
                log.warn("JSON 文件中无数据 (data 字段为空)");
                return;
            }

            int totalItems = dataNode.size();
            String fetchTime = root.has("fetchTime") ? root.get("fetchTime").asText() : "未知";
            log.info("抓取时间: {}, 总条数: {}", fetchTime, totalItems);

            // 2. 转换并批量写入
            log.info("[2/3] 转换并写入数据库...");
            List<Stock> batchBuffer = new ArrayList<>();
            int imported = 0;

            for (JsonNode item : dataNode)
            {
                Stock stock = convertToStock(item);
                if (stock.getStockCode() == null)
                {
                    log.trace("跳过无股票代码的数据: {}", item);
                    continue;
                }
                batchBuffer.add(stock);
                imported++;

                if (batchBuffer.size() >= DB_BATCH_SIZE)
                {
                    flushBatch(batchBuffer);
                }
            }
            if (!batchBuffer.isEmpty())
            {
                flushBatch(batchBuffer);
            }

            log.info("[3/3] 完成: 导入 {} 条行情数据", imported);
        }
        catch (Exception e)
        {
            log.error("同步失败: {}", e.getMessage(), e);
        }

        log.info("========== 同步结束 ==========");
    }

    // ==================== 数据转换 ====================

    private Stock convertToStock(JsonNode item)
    {
        Stock stock = new Stock();
        LocalDateTime now = LocalDateTime.now();

        stock.setStockCode(getString(item, "code", "stockCode", "stock_code", "symbol"));
        stock.setStockName(getString(item, "name", "stockName", "stock_name"));
        stock.setMarket(inferMarket(item));
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

    private String inferMarket(JsonNode item)
    {
        // 优先使用 market 字段
        String market = getString(item, "market", "m", "type");
        if (market != null && !market.isEmpty()) return market.toUpperCase();

        // 根据股票代码推断
        String code = getString(item, "code", "stockCode", "stock_code", "symbol");
        if (code == null || code.length() < 6) return null;
        char first = code.charAt(0);
        if (first == '6' || first == '9') return "SH";
        if (first == '0' || first == '2' || first == '3') return "SZ";
        if (first == '8' || first == '4') return "BJ";
        return null;
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

    private void flushBatch(List<Stock> batch)
    {
        if (batch.isEmpty()) return;
        try
        {
            stockMapper.batchInsertListIgnoreSame(batch);
            log.info("批量写入: {} 条", batch.size());
            batch.clear();
        }
        catch (Exception e)
        {
            log.error("批量写入失败 ({} 条): {}", batch.size(), e.getMessage());
            if (batch.size() > DB_BATCH_SIZE * 3) batch.clear();
        }
    }
}
