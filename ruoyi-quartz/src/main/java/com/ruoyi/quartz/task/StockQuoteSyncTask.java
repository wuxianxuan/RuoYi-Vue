package com.ruoyi.quartz.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockFundFlow;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockFundFlowMapper;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同花顺个股行情同步定时任务
 *
 * 数据来源：stock-tools Node.js 脚本抓取的 JSON 文件 (../stock-tools/data/ths_quotes.json)
 *
 * 数据字段（实际抓取格式）：
 *   code 股票代码 / name 股票名称 / now 现价 / change 涨跌幅% / change_amount 涨跌额 /
 *   speed 涨速% / turnover 换手率% / vol_ratio 量比 / amplitude 振幅% /
 *   amount 成交额(带"亿"/"万"单位) / float_share 流通股(带"万"/"亿"单位) /
 *   circ_mv 流通市值(带"亿"单位) / pe 市盈率("亏损"/"--" 表示无效)
 *
 * 写入策略（拆分）:
 *   stock_daily_quote  → 每日行情快照（价格/PE/流通市值/换手率等），INSERT IGNORE 保留全部历史
 *   stock 表           → 身份 + 最新快照（快照字段取自 stock_daily_quote，market 按代码推导）；
 *                        同步末尾检查本次代码是否已存在，缺失则创建，已存在则更新最新快照
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

    @Autowired
    private StockFundFlowMapper stockFundFlowMapper;

    @Value("${stock.ths.quote.data-file:../stock-tools/data/ths_quotes.json}")
    private String dataFilePath;

    @Value("${stock.ths.funds.concept-file:../stock-tools/data/ths_funds_concept.json}")
    private String fundFlowConceptFile;

    @Value("${stock.ths.funds.industry-file:../stock-tools/data/ths_funds_industry.json}")
    private String fundFlowIndustryFile;

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

            // 2. 转换：写入历史 + 收集最新快照
            log.info("[2/4] 转换数据...");
            List<Stock> stockList = new ArrayList<>();
            List<StockDailyQuote> quoteBatch = new ArrayList<>();
            Set<String> syncedCodes = new LinkedHashSet<>();
            int total = 0;

            for (JsonNode item : dataNode)
            {
                StockDailyQuote quote = buildDailyQuote(item, tradeDate);
                Stock stock = buildStock(item, quote);

                if (stock.getStockCode() == null)
                {
                    log.trace("跳过无效数据: {}", item);
                    continue;
                }

                stockList.add(stock);
                quoteBatch.add(quote);
                syncedCodes.add(stock.getStockCode());
                total++;

                if (quoteBatch.size() >= DB_BATCH_SIZE)
                {
                    flushQuoteBatch(quoteBatch);
                }
            }
            if (!quoteBatch.isEmpty())
            {
                flushQuoteBatch(quoteBatch);
            }

            // 3. 检查本次同步的股票是否已存在，缺失则创建
            log.info("[3/4] 检查股票是否已存在...");
            List<String> existingCodes = stockMapper.selectStockCodesByCodes(new ArrayList<>(syncedCodes));
            Set<String> existingSet = new HashSet<>(existingCodes);
            int newCount = 0;
            for (String code : syncedCodes)
            {
                if (!existingSet.contains(code))
                {
                    newCount++;
                }
            }
            log.info("本次同步 {} 只股票：新增 {} 只，已存在 {} 只", total, newCount, total - newCount);

            // 4. 批量 upsert stock（缺失则创建、已存在则更新最新快照）
            log.info("[4/4] 写入 stock 最新快照...");
            for (int i = 0; i < stockList.size(); i += DB_BATCH_SIZE)
            {
                int end = Math.min(i + DB_BATCH_SIZE, stockList.size());
                flushStockBatch(new ArrayList<>(stockList.subList(i, end)));
            }

            log.info("stock_daily_quote 快照已保存 (date={})", tradeDate);
            log.info("========== 完成: {} 条 ==========", total);
        }
        catch (Exception e)
        {
            log.error("同步失败: {}", e.getMessage(), e);
        }

        // 行情处理完成后，额外同步行业/概念资金流向
        syncFundFlow();
    }

    // ==================== 资金流向同步 ====================

    /**
     * 同步行业/概念资金流向（ths_funds_concept.json / ths_funds_industry.json）
     * 写入 stock_fund_flow 表，按 (plate_type, trade_name, trade_date) INSERT IGNORE 保留全部历史。
     */
    private void syncFundFlow()
    {
        log.info("========== 开始同步行业/概念资金流向 ==========");
        syncFundFlowFile(fundFlowConceptFile, "CONCEPT");
        syncFundFlowFile(fundFlowIndustryFile, "INDUSTRY");
        log.info("========== 资金流向同步结束 ==========");
    }

    private void syncFundFlowFile(String filePath, String plateType)
    {
        try
        {
            File file = new File(filePath);
            if (!file.exists())
            {
                log.warn("资金流向数据文件不存在，跳过: {}", file.getAbsolutePath());
                return;
            }

            JsonNode root = objectMapper.readTree(file);
            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isArray() || dataNode.size() == 0)
            {
                log.warn("资金流向 JSON 文件中无数据: {}", filePath);
                return;
            }

            String fetchTime = root.has("fetchTime") ? root.get("fetchTime").asText() : "";
            LocalDate tradeDate = parseTradeDate(fetchTime);
            log.info("资金流向[{}] 交易日期: {}, 总条数: {}", plateType, tradeDate, dataNode.size());

            List<StockFundFlow> batch = new ArrayList<>();
            for (JsonNode item : dataNode)
            {
                StockFundFlow flow = buildFundFlow(item, plateType, tradeDate);
                if (flow.getUrl() == null)
                {
                    log.trace("跳过无效资金流向数据: {}", item);
                    continue;
                }
                batch.add(flow);
                if (batch.size() >= DB_BATCH_SIZE)
                {
                    flushFundFlowBatch(batch);
                }
            }
            if (!batch.isEmpty())
            {
                flushFundFlowBatch(batch);
            }
            log.info("资金流向[{}] 已保存 (date={})", plateType, tradeDate);
        }
        catch (Exception e)
        {
            log.error("资金流向[{}] 同步失败: {}", plateType, e.getMessage(), e);
        }
    }

    /** 构建资金流向实体（行业/概念通用） */
    private StockFundFlow buildFundFlow(JsonNode item, String plateType, LocalDate tradeDate)
    {
        StockFundFlow flow = new StockFundFlow();
        flow.setPlateType(plateType);
        flow.setTradeDate(tradeDate == null ? null : Date.valueOf(tradeDate));
        flow.setTradeName(getString(item, "tradename"));
        flow.setUrl(getString(item, "url"));
        flow.setPrice(getDecimal(item, "price"));
        flow.setTradeZdf(getPercent(item, "tradezdf"));
        flow.setBuy(getDecimal(item, "buy"));
        flow.setSell(getDecimal(item, "sell"));
        flow.setJe(getDecimal(item, "je"));
        flow.setTotal(getInteger(item, "total"));
        return flow;
    }

    private void flushFundFlowBatch(List<StockFundFlow> batch)
    {
        if (batch.isEmpty()) return;
        try
        {
            stockFundFlowMapper.batchInsertIgnore(batch);
            log.debug("stock_fund_flow 写入: {} 条", batch.size());
            batch.clear();
        }
        catch (Exception e)
        {
            log.error("stock_fund_flow 写入失败 ({} 条): {}", batch.size(), e.getMessage());
            if (batch.size() > DB_BATCH_SIZE * 3) batch.clear();
        }
    }

    // ==================== 数据构建 ====================

    /** 构建 Stock（身份 + 最新快照，快照字段直接取自 stock_daily_quote，market 按代码推导） */
    private Stock buildStock(JsonNode item, StockDailyQuote quote)
    {
        Stock stock = new Stock();
        stock.setStockCode(quote.getStockCode());
        stock.setStockName(getString(item, "name", "stockName", "stock_name"));
        stock.setMarket(inferMarket(quote.getStockCode()));
        // 快照字段取自 stock_daily_quote 最新数据
        stock.setCurrentPrice(quote.getCurrentPrice());
        stock.setChangeRate(quote.getChangeRate());
        stock.setPriceChange(quote.getPriceChange());
        stock.setSpeed(quote.getSpeed());
        stock.setTurnoverRate(quote.getTurnoverRate());
        stock.setVolumeRatio(quote.getVolumeRatio());
        stock.setAmplitude(quote.getAmplitude());
        stock.setTurnoverAmount(quote.getTurnoverAmount());
        stock.setCirculatingShares(quote.getCirculatingShares());
        stock.setCircMarketCap(quote.getCircMarketCap());
        stock.setPeRatio(quote.getPeRatio());
        stock.setCreateBy("ths-sync");
        stock.setUpdateBy("ths-sync");
        stock.setUpdateTime(java.sql.Timestamp.valueOf(LocalDateTime.now()));
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
        q.setTurnoverAmount(getDecimalWithUnit(item, "amount", "turnover_amount", "cje", "cjl"));
        q.setCirculatingShares(getDecimalWithUnit(item, "float_share", "circulating_shares", "ltg"));
        q.setPeRatio(getDecimal(item, "pe", "pe_ratio", "peRatio", "per"));
        q.setCircMarketCap(getDecimalWithUnit(item, "circ_mv", "circ_market_cap", "circ_market_value"));
        return q;
    }

    /** 根据股票代码规则生成所属市场交易所 */
    private String inferMarket(String code)
    {
        if (code == null || code.length() < 6) return null;
        char first = code.charAt(0);
        if (first == '6') return "SH";                              // 沪市 A股/科创板 600/601/603/605/688/689
        if (first == '9') return code.startsWith("920") ? "BJ" : "SH"; // 920 北交所新代码段 / 900 沪B(数据源无B股，兜底SH)
        if (first == '0' || first == '2' || first == '3') return "SZ"; // 深市主板/中小板/创业板
        if (first == '8' || first == '4') return "BJ";              // 北交所老代码段 43x/83x/87x
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

    /**
     * 解析带单位后缀的数值（同花顺行情中成交额/流通股/流通市值带"亿"/"万"后缀）
     * 例: "4.74亿" → 474000000, "1788.00万" → 17880000, "5.10亿" → 510000000
     * 无效值("-"/"--"/空/非数字) 返回 null
     */
    private BigDecimal getDecimalWithUnit(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull())
            {
                String text = field.asText();
                if (text == null || text.isEmpty() || "-".equals(text) || "--".equals(text))
                {
                    continue;
                }
                try
                {
                    BigDecimal multiplier = BigDecimal.ONE;
                    if (text.endsWith("亿"))
                    {
                        multiplier = new BigDecimal("100000000");
                        text = text.substring(0, text.length() - 1);
                    }
                    else if (text.endsWith("万"))
                    {
                        multiplier = new BigDecimal("10000");
                        text = text.substring(0, text.length() - 1);
                    }
                    return new BigDecimal(text).multiply(multiplier);
                }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    /** 解析带 % 后缀的数值（如涨跌幅 "3.06%" → 3.06），无效值返回 null */
    private BigDecimal getPercent(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull())
            {
                String text = field.asText().trim();
                if (text.endsWith("%"))
                {
                    text = text.substring(0, text.length() - 1);
                }
                if (text.isEmpty() || "-".equals(text) || "--".equals(text))
                {
                    continue;
                }
                try
                {
                    return new BigDecimal(text);
                }
                catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    /** 解析整数值（如公司家数 "15" → 15），无效值返回 null */
    private Integer getInteger(JsonNode node, String... names)
    {
        for (String name : names)
        {
            JsonNode field = node.get(name);
            if (field != null && !field.isNull())
            {
                String text = field.asText().trim();
                if (text.isEmpty()) continue;
                try
                {
                    return Integer.valueOf(text);
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
