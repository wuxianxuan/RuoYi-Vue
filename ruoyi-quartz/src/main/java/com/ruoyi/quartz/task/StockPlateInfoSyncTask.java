package com.ruoyi.quartz.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.BlockRankResponse;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockPlateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 同花顺板块/概念排名数据同步定时任务
 *
 * 从 stock_plate 表读取板块/概念记录，逐个按其 plate_code 调用同花顺接口获取成分股排名，
 * 按 stock_code 持久化股票并同步板块-股票关联到 stock_plate_stock 表（关联用 plate_code + stock_code）。
 * plate_code 为空/缺的板块会跳过。
 *
 * API: https://d.10jqka.com.cn/v2/blockrank/{plateCode}/{sortField}/d1000.js
 * 示例: https://d.10jqka.com.cn/v2/blockrank/881145/199112/d1000.js (电力板块)
 *
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockPlateInfoSyncTask.sync()
 * 建议 cron: 0 0 16 ? * MON-FRI（每个交易日下午4:00）
 *
 * @author ruoyi
 */
@Component("stockPlateInfoSyncTask")
public class StockPlateInfoSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockPlateInfoSyncTask.class);

    /** 同花顺板块排名API基础地址 */
    private static final String BLOCK_RANK_API = "https://d.10jqka.com.cn/v2/blockrank";

    /** 默认排名周期 */
    private static final String DEFAULT_PERIOD = "d1000";

    /** 默认排序字段: 涨跌幅 */
    private static final String DEFAULT_SORT = "199112";

    /** JSONP 包装函数名正则 */
    private static final Pattern JSONP_PATTERN = Pattern.compile("^[a-zA-Z_][\\w]*\\((.*)\\)\\s*$");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockPlateMapper stockPlateMapper;

    // ==================== 可配置属性 ====================

    @Value("${stock.blockrank.origin:https://m.10jqka.com.cn}")
    private String origin;

    @Value("${stock.blockrank.referer:https://m.10jqka.com.cn/}")
    private String referer;

    /** 是否自动创建 stock 表中不存在的股票记录 */
    @Value("${stock.blockrank.autoCreateStock:true}")
    private boolean autoCreateStock;

    /** 同步时是否全量替换板块-股票关联（false=仅追加） */
    @Value("${stock.blockrank.fullReplace:true}")
    private boolean fullReplace;

    // ==================== 对外调用入口 ====================

    /**
     * 从 stock_plate 表读取板块记录，逐个同步其成分股
     */
    public void sync()
    {
        log.info("========== 开始同步同花顺板块/概念数据（从 stock_plate 读取） ==========");

        // 1. 从 stock_plate 读取所有板块
        List<StockPlate> plates = stockPlateMapper.selectPlateList(new StockPlate());
        if (plates == null || plates.isEmpty())
        {
            log.warn("stock_plate 表中无板块记录，跳过同步");
            return;
        }

        // 2. 过滤出带 plate_code 的板块
        List<StockPlate> syncPlates = plates.stream()
                .filter(p -> p.getPlateCode() != null && !p.getPlateCode().trim().isEmpty())
                .collect(Collectors.toList());
        if (syncPlates.isEmpty())
        {
            log.warn("stock_plate 中没有带 plate_code 的板块，跳过同步");
            return;
        }
        log.info("从 stock_plate 读取 {} 个板块，其中 {} 个带 plate_code 需同步",
                plates.size(), syncPlates.size());

        SyncResult totalResult = new SyncResult();
        for (StockPlate plate : syncPlates)
        {
            try
            {
                SyncResult result = syncPlate(plate);
                totalResult.add(result);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
                log.warn("同步被中断");
                break;
            }
            catch (Exception e)
            {
                log.error("板块 [{}] {} 同步异常", plate.getPlateCode(), plate.getPlateName(), e);
                totalResult.failedPlates++;
            }

            // 请求间隔，避免被反爬
            if (syncPlates.size() > 1)
            {
                try { Thread.sleep(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.info("========== 板块同步完成: 成功{}板块, 失败{}板块, 新增股票{}只, 更新股票{}只, 关联{}条 ==========",
                totalResult.successPlates, totalResult.failedPlates,
                totalResult.stocksCreated, totalResult.stocksUpdated,
                totalResult.associationsSaved);
    }

    /**
     * 同步单个板块/概念
     */
    private SyncResult syncPlate(StockPlate plate) throws InterruptedException
    {
        SyncResult result = new SyncResult();
        String plateCode = plate.getPlateCode();

        // 1. 调用 API 获取板块排名数据
        BlockRankResponse response = fetchBlockRank(plateCode, DEFAULT_SORT, DEFAULT_PERIOD);
        if (response == null || response.getItems() == null || response.getItems().isEmpty())
        {
            log.warn("板块 [{}] 无数据返回，跳过", plateCode);
            result.failedPlates++;
            return result;
        }

        List<BlockRankResponse.BlockRankItem> items = response.getItems();
        log.info("━━━ [{}] {} (plateCode:{}) — 成分股{}只 ━━━",
                plate.getPlateType(), plate.getPlateName(), plateCode, items.size());

        // 2. 持久化股票记录（按 stock_code），返回股票代码集合
        PersistResult pr = persistStocks(items);
        result.stocksCreated = pr.created;
        result.stocksUpdated = pr.updated;

        // 3. 同步板块-股票关联 (plate_code, stock_code)
        result.associationsSaved = syncPlateStockAssociations(plateCode, pr.stockCodes);
        result.successPlates = 1;

        // 4. 日志输出
        logTopStocks(items, DEFAULT_SORT);

        return result;
    }

    // ==================== 股票持久化 ====================

    /**
     * 从排名数据中持久化股票记录（按 stock_code），已存在则更新名称/PE/流通市值，不存在则自动创建
     *
     * @param items 板块排名中的股票列表
     * @return 股票代码集合 + 新增/更新数量
     */
    private PersistResult persistStocks(List<BlockRankResponse.BlockRankItem> items)
    {
        PersistResult pr = new PersistResult();

        // 1. 提取所有股票代码
        List<String> stockCodes = items.stream()
                .map(BlockRankResponse.BlockRankItem::getStockCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodes.isEmpty())
        {
            return pr;
        }

        // 2. 批量查询已存在的股票
        List<Stock> existingStocks = stockPlateMapper.selectStocksByCodeBatch(stockCodes);
        Map<String, Stock> existingMap = existingStocks.stream()
                .collect(Collectors.toMap(Stock::getStockCode, s -> s, (a, b) -> a));

        // 3. 构建 code → name / code → item 映射
        Map<String, String> codeNameMap = items.stream()
                .filter(i -> i.getStockCode() != null && i.getStockName() != null)
                .collect(Collectors.toMap(
                        BlockRankResponse.BlockRankItem::getStockCode,
                        BlockRankResponse.BlockRankItem::getStockName,
                        (a, b) -> a));

        Map<String, BlockRankResponse.BlockRankItem> codeItemMap = items.stream()
                .filter(i -> i.getStockCode() != null)
                .collect(Collectors.toMap(
                        BlockRankResponse.BlockRankItem::getStockCode,
                        i -> i,
                        (a, b) -> a));

        // 4. 分类处理
        List<Stock> newStocks = new ArrayList<>();
        List<Stock> updateStocks = new ArrayList<>();

        for (String code : stockCodes)
        {
            String name = codeNameMap.get(code);
            String market = inferMarket(code);
            BlockRankResponse.BlockRankItem item = codeItemMap.get(code);

            if (existingMap.containsKey(code))
            {
                Stock s = existingMap.get(code);
                boolean changed = false;
                if (name != null && !name.equals(s.getStockName()))
                {
                    s.setStockName(name);
                    changed = true;
                }
                if (s.getMarket() == null && market != null)
                {
                    s.setMarket(market);
                    changed = true;
                }
                if (item != null && enrichStockFromItem(s, item))
                {
                    changed = true;
                }
                if (changed)
                {
                    updateStocks.add(s);
                }
            }
            else if (autoCreateStock)
            {
                Stock s = new Stock();
                s.setStockCode(code);
                s.setStockName(name);
                s.setMarket(market);
                if (item != null)
                {
                    s.setPeRatio(item.getPeRatio());
                    s.setCircMarketCap(item.getCircMarketCap());
                }
                s.setCreateBy("system");
                newStocks.add(s);
            }
            else
            {
                log.debug("股票不在库中且未开启自动创建: {} {}", code, name);
            }
        }

        // 5. 批量插入/更新
        if (!newStocks.isEmpty())
        {
            stockMapper.batchInsertListIgnoreSame(newStocks);
            log.info("新增股票 {} 只", newStocks.size());
        }
        if (!updateStocks.isEmpty())
        {
            for (Stock s : updateStocks)
            {
                stockMapper.updateStock(s);
            }
            log.debug("更新股票 {} 只", updateStocks.size());
        }

        pr.stockCodes = stockCodes;
        pr.created = newStocks.size();
        pr.updated = updateStocks.size();
        log.info("股票处理: 已有{}只, 新增{}只, 更新{}只",
                existingStocks.size(), newStocks.size(), updateStocks.size());
        return pr;
    }

    /**
     * 用 BlockRankItem 中的PE/流通市值数据更新 Stock 对象
     *
     * @return true 表示有数据被更新
     */
    private boolean enrichStockFromItem(Stock stock, BlockRankResponse.BlockRankItem item)
    {
        boolean changed = false;

        if (item.getPeRatio() != null)
        {
            stock.setPeRatio(item.getPeRatio());
            changed = true;
        }
        if (item.getCircMarketCap() != null)
        {
            stock.setCircMarketCap(item.getCircMarketCap());
            changed = true;
        }

        return changed;
    }

    /**
     * 根据股票代码推断市场
     */
    private String inferMarket(String stockCode)
    {
        if (stockCode == null || stockCode.length() < 6) return null;
        char first = stockCode.charAt(0);
        if (first == '6' || first == '9') return "SH";
        if (first == '0' || first == '2' || first == '3') return "SZ";
        if (first == '8' || first == '4') return "BJ";
        return null;
    }

    // ==================== 关联同步 ====================

    /**
     * 同步板块-股票关联（按 plate_code + stock_code）
     *
     * @param plateCode  板块代码
     * @param stockCodes 股票代码列表
     * @return 保存的关联数量
     */
    private int syncPlateStockAssociations(String plateCode, List<String> stockCodes)
    {
        if (plateCode == null || stockCodes == null || stockCodes.isEmpty())
        {
            return 0;
        }

        if (fullReplace)
        {
            stockPlateMapper.deletePlateStocksByPlateCode(plateCode);
            log.debug("清空板块 [{}] 原有关联", plateCode);
        }

        String[] codeArray = stockCodes.toArray(new String[0]);
        int rows = stockPlateMapper.insertPlateStocks(plateCode, codeArray);

        log.info("板块 [{}] 关联同步完成: {} 只股票, 模式={}",
                plateCode, stockCodes.size(), fullReplace ? "全量替换" : "增量追加");
        return rows;
    }

    // ==================== API 调用 ====================

    /**
     * 调用同花顺板块排名API
     */
    public BlockRankResponse fetchBlockRank(String plateCode, String sortField, String period)
    {
        String url = String.format("%s/%s/%s/%s.js", BLOCK_RANK_API, plateCode, sortField, period);
        log.info("请求同花顺板块排名: {}", url);

        try
        {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "text/javascript, application/javascript, application/x-javascript");
            headers.set("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
            headers.set("Origin", origin);
            headers.set("Referer", referer);
            headers.set("sec-ch-ua", "\"Chromium\";v=\"148\", \"Microsoft Edge\";v=\"148\", \"Not/A)Brand\";v=\"99\"");
            headers.set("sec-ch-ua-mobile", "?0");
            headers.set("sec-ch-ua-platform", "\"Windows\"");

            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);

            String body = responseEntity.getBody();
            if (body == null || body.isEmpty())
            {
                log.warn("同花顺API返回空数据: url={}", url);
                return null;
            }

            return parseResponse(body, plateCode, sortField);
        }
        catch (Exception e)
        {
            log.error("同花顺API调用失败: url={}", url, e);
            return null;
        }
    }

    // ==================== 响应解析 ====================

    /**
     * 解析 JSONP 响应为 BlockRankResponse
     */
    public BlockRankResponse parseResponse(String body, String plateCode, String sortField)
    {
        try
        {
            String json = extractJson(body);
            if (json == null)
            {
                log.warn("无法从响应中提取JSON: plateCode={}, body前100字符={}",
                        plateCode, body.substring(0, Math.min(100, body.length())));
                return null;
            }

            BlockRankResponse response = objectMapper.readValue(json, BlockRankResponse.class);
            log.debug("解析成功: plateCode={}, plateName={}, itemCount={}",
                    plateCode, response.getPlateName(),
                    response.getItems() != null ? response.getItems().size() : 0);

            return response;
        }
        catch (Exception e)
        {
            log.error("解析同花顺响应失败: plateCode={}, sortField={}", plateCode, sortField, e);
            return null;
        }
    }

    /**
     * 从 JSONP 响应中提取纯 JSON 字符串
     */
    static String extractJson(String body)
    {
        if (body == null || body.trim().isEmpty()) return null;

        String trimmed = body.trim();

        Matcher matcher = JSONP_PATTERN.matcher(trimmed);
        if (matcher.matches())
        {
            return matcher.group(1);
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start)
        {
            return trimmed.substring(start, end + 1);
        }

        return null;
    }

    // ==================== 日志输出 ====================

    /**
     * 输出 Top 10 排名及涨跌统计
     */
    private void logTopStocks(List<BlockRankResponse.BlockRankItem> items, String sortField)
    {
        int topN = Math.min(10, items.size());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n%-4s %-8s %-8s %-10s %-8s %-8s%n",
                "排名", "股票代码", "股票名称", "最新价", "涨跌幅%", "换手率%"));
        sb.append("─".repeat(55)).append("\n");

        for (int i = 0; i < topN; i++)
        {
            BlockRankResponse.BlockRankItem item = items.get(i);
            BigDecimal sortVal = item.getSortValue(sortField);
            sb.append(String.format("%-4d %-8s %-8s %-10s %-8s %-8s%n",
                    i + 1,
                    item.getStockCode(),
                    item.getStockName(),
                    item.getPrice() != null ? item.getPrice().toString() : "-",
                    sortVal != null ? sortVal.toString() : "-",
                    item.getTurnoverRate() != null ? item.getTurnoverRate().toString() : "-"));
        }
        log.info(sb.toString());

        // 涨跌分布
        int upCount = 0, downCount = 0, flatCount = 0;
        BigDecimal maxUp = BigDecimal.valueOf(-999);
        BigDecimal maxDown = BigDecimal.valueOf(999);
        String maxUpName = "", maxDownName = "";

        for (BlockRankResponse.BlockRankItem item : items)
        {
            BigDecimal change = item.getSortValue(sortField);
            if (change == null) continue;
            int cmp = change.compareTo(BigDecimal.ZERO);
            if (cmp > 0) { upCount++; if (change.compareTo(maxUp) > 0) { maxUp = change; maxUpName = item.getStockName(); } }
            else if (cmp < 0) { downCount++; if (change.compareTo(maxDown) < 0) { maxDown = change; maxDownName = item.getStockName(); } }
            else { flatCount++; }
        }

        log.info("涨跌分布: 上涨{}只, 下跌{}只, 平盘{}只 | 领涨: {} ({}), 领跌: {} ({})",
                upCount, downCount, flatCount, maxUpName, maxUp, maxDownName, maxDown);
    }

    // ==================== 内部类 ====================

    /** 股票持久化结果 */
    private static class PersistResult
    {
        List<String> stockCodes = new ArrayList<>();
        int created = 0;
        int updated = 0;
    }

    /** 同步结果统计 */
    private static class SyncResult
    {
        int successPlates = 0;
        int failedPlates = 0;
        int stocksCreated = 0;
        int stocksUpdated = 0;
        int associationsSaved = 0;

        void add(SyncResult other)
        {
            this.successPlates += other.successPlates;
            this.failedPlates += other.failedPlates;
            this.stocksCreated += other.stocksCreated;
            this.stocksUpdated += other.stocksUpdated;
            this.associationsSaved += other.associationsSaved;
        }
    }
}
