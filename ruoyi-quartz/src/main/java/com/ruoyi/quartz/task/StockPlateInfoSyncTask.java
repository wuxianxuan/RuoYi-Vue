package com.ruoyi.quartz.task;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.BlockRankResponse;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockPlate;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockPlateMapper;
import com.ruoyi.system.service.IStockPlateService;
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
 * 通过同花顺接口获取指定板块/概念的成分股排名数据（按涨跌幅等指标排序），
 * 自动同步板块信息到 stock_plate 表，同步板块-股票关联到 stock_plate_stock 表。
 *
 * API: https://d.10jqka.com.cn/v2/blockrank/{plateCode}/{sortField}/d30.js
 * 示例: https://d.10jqka.com.cn/v2/blockrank/881145/199112/d30.js (电力板块)
 *
 * 通过 RuoYi 定时任务管理页面配置，调用目标字符串: stockPlateInfoSyncTask.sync()
 * 建议 cron: 0 0 16 * * MON-FRI（每个交易日下午4:00）
 *
 * @author ruoyi
 */
@Component("stockPlateInfoSyncTask")
public class StockPlateInfoSyncTask
{
    private static final Logger log = LoggerFactory.getLogger(StockPlateInfoSyncTask.class);

    /** 同花顺板块排名API基础地址 */
    private static final String BLOCK_RANK_API = "https://d.10jqka.com.cn/v2/blockrank";

    /** 默认排名周期(30天) */
    private static final String DEFAULT_PERIOD = "d1000";

    /** 默认排序字段: 涨跌幅 */
    private static final String DEFAULT_SORT = "199112";

    /** JSONP 包装函数名正则 */
    private static final Pattern JSONP_PATTERN = Pattern.compile("^[a-zA-Z_][\\w]*\\((.*)\\)\\s*$");

    /**
     * 默认板块配置 — 同花顺代码 → (板块名称, 板块类型)
     * 取自同花顺热门行业榜+热门概念榜
     * 类型: CONCEPT=概念板块, INDUSTRY=行业板块
     */
    private static final Map<String, PlateConfig> DEFAULT_PLATES = new LinkedHashMap<>();
    static {
        // ========== 热门行业板块（INDUSTRY） ==========
        DEFAULT_PLATES.put("881145", new PlateConfig("电力", "INDUSTRY"));
        DEFAULT_PLATES.put("881270", new PlateConfig("元件", "INDUSTRY"));
        DEFAULT_PLATES.put("881121", new PlateConfig("半导体", "INDUSTRY"));
        DEFAULT_PLATES.put("881129", new PlateConfig("通信设备", "INDUSTRY"));
        DEFAULT_PLATES.put("881157", new PlateConfig("证券", "INDUSTRY"));
        DEFAULT_PLATES.put("881105", new PlateConfig("煤炭开采加工", "INDUSTRY"));
        DEFAULT_PLATES.put("881170", new PlateConfig("小金属", "INDUSTRY"));
        DEFAULT_PLATES.put("881273", new PlateConfig("白酒", "INDUSTRY"));
        DEFAULT_PLATES.put("881278", new PlateConfig("电网设备", "INDUSTRY"));
        DEFAULT_PLATES.put("881169", new PlateConfig("贵金属", "INDUSTRY"));
        DEFAULT_PLATES.put("881168", new PlateConfig("工业金属", "INDUSTRY"));
        DEFAULT_PLATES.put("881158", new PlateConfig("零售", "INDUSTRY"));
        DEFAULT_PLATES.put("884054", new PlateConfig("铜", "INDUSTRY"));
        DEFAULT_PLATES.put("881124", new PlateConfig("消费电子", "INDUSTRY"));
        DEFAULT_PLATES.put("881281", new PlateConfig("电池", "INDUSTRY"));
        DEFAULT_PLATES.put("881164", new PlateConfig("文化传媒", "INDUSTRY"));
        DEFAULT_PLATES.put("881122", new PlateConfig("光学光电子", "INDUSTRY"));
        DEFAULT_PLATES.put("881155", new PlateConfig("银行", "INDUSTRY"));
        DEFAULT_PLATES.put("881279", new PlateConfig("光伏设备", "INDUSTRY"));
        DEFAULT_PLATES.put("881272", new PlateConfig("软件开发", "INDUSTRY"));

        // ========== 热门概念板块（CONCEPT） ==========
        DEFAULT_PLATES.put("886033", new PlateConfig("共封装光学(CPO)", "CONCEPT"));
        DEFAULT_PLATES.put("886084", new PlateConfig("光纤概念", "CONCEPT"));
        DEFAULT_PLATES.put("885959", new PlateConfig("PCB概念", "CONCEPT"));
        DEFAULT_PLATES.put("886071", new PlateConfig("AI PC", "CONCEPT"));
        DEFAULT_PLATES.put("886042", new PlateConfig("存储芯片", "CONCEPT"));
        DEFAULT_PLATES.put("885887", new PlateConfig("数据中心(AIDC)", "CONCEPT"));
        DEFAULT_PLATES.put("885937", new PlateConfig("培育钻石", "CONCEPT"));
        DEFAULT_PLATES.put("886078", new PlateConfig("商业航天", "CONCEPT"));
        DEFAULT_PLATES.put("885886", new PlateConfig("超级电容", "CONCEPT"));
        DEFAULT_PLATES.put("886069", new PlateConfig("人形机器人", "CONCEPT"));
        DEFAULT_PLATES.put("886073", new PlateConfig("铜缆高速连接", "CONCEPT"));
        DEFAULT_PLATES.put("886009", new PlateConfig("先进封装", "CONCEPT"));
        DEFAULT_PLATES.put("886108", new PlateConfig("AI应用", "CONCEPT"));
        DEFAULT_PLATES.put("886050", new PlateConfig("算力租赁", "CONCEPT"));
        DEFAULT_PLATES.put("885699", new PlateConfig("ST板块", "CONCEPT"));
        DEFAULT_PLATES.put("885517", new PlateConfig("机器人概念", "CONCEPT"));
        DEFAULT_PLATES.put("886044", new PlateConfig("液冷服务器", "CONCEPT"));
        DEFAULT_PLATES.put("885756", new PlateConfig("芯片概念", "CONCEPT"));
        DEFAULT_PLATES.put("886015", new PlateConfig("创新药", "CONCEPT"));
        DEFAULT_PLATES.put("886019", new PlateConfig("AIGC概念", "CONCEPT"));
    }

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockPlateMapper stockPlateMapper;

    @Autowired
    private IStockPlateService stockPlateService;

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
     * 无参调用：使用默认板块列表同步
     */
    public void sync()
    {
        sync(null);
    }

    /**
     * 带参调用
     *
     * @param params 参数格式支持：
     *               1. 空或 null → 使用默认板块列表
     *               2. 纯数字 → 单个板块代码，如 "881145"
     *               3. "plateCode1,plateCode2" → 多个板块代码，逗号分隔
     *               4. "plateCode:sortField" → 指定板块和排序字段
     */
    public void sync(String params)
    {
        log.info("========== 开始同步同花顺板块/概念数据 ==========");

        List<SyncConfig> configs = parseParams(params);

        SyncResult totalResult = new SyncResult();
        for (SyncConfig config : configs)
        {
            try
            {
                SyncResult result = syncPlate(config);
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
                log.error("板块 [{}] 同步异常", config.plateCode, e);
                totalResult.failedPlates++;
            }

            // 请求间隔，避免被反爬
            if (configs.size() > 1)
            {
                try { Thread.sleep(500); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }

        log.info("========== 板块同步完成: 成功{}板块, 失败{}板块, 新增/更新股票{}只, 关联{}条 ==========",
                totalResult.successPlates, totalResult.failedPlates,
                totalResult.stocksCreated + totalResult.stocksUpdated,
                totalResult.associationsSaved);
    }

    /**
     * 同步单个板块/概念
     */
    private SyncResult syncPlate(SyncConfig config) throws InterruptedException
    {
        SyncResult result = new SyncResult();
        String thsCode = config.plateCode;

        // 1. 调用 API 获取板块排名数据
        BlockRankResponse response = fetchBlockRank(thsCode, config.sortField, DEFAULT_PERIOD);
        if (response == null || response.getItems() == null || response.getItems().isEmpty())
        {
            log.warn("板块 [{}] 无数据返回，跳过", thsCode);
            result.failedPlates++;
            return result;
        }

        String plateName = response.getPlateName();
        List<BlockRankResponse.BlockRankItem> items = response.getItems();

        log.info("━━━ [{}] {} (同花顺代码:{}) — 成分股{}只 ━━━",
                config.plateType, plateName, thsCode, items.size());

        // 2. 持久化板块记录
        Long plateId = upsertPlate(thsCode, plateName, config.plateType);

        // 3. 持久化股票记录 & 收集 stock_id
        List<Long> stockIds = persistStocks(items);

        // 4. 同步板块-股票关联
        int assocCount = syncPlateStockAssociations(plateId, stockIds);
        result.associationsSaved = assocCount;
        result.successPlates = 1;

        // 5. 日志输出
        logTopStocks(items, config.sortField);

        return result;
    }

    // ==================== 板块持久化 ====================

    /**
     * 查找或创建板块记录
     *
     * @param thsCode   同花顺板块代码
     * @param plateName 板块名称（从API获取）
     * @param plateType 板块类型 INDUSTRY/CONCEPT
     * @return 板块ID
     */
    private Long upsertPlate(String thsCode, String plateName, String plateType)
    {
        // 1. 按名称+类型查找是否已存在
        StockPlate existing = stockPlateMapper.selectPlateByNameAndType(plateName, plateType);
        if (existing != null)
        {
            // 更新 remark 字段存储同花顺代码（方便后续查询）
            if (!thsCode.equals(existing.getRemark()))
            {
                existing.setRemark(thsCode);
                stockPlateService.updatePlate(existing);
            }
            log.debug("板块已存在: id={}, name={}, type={}", existing.getId(), plateName, plateType);
            return existing.getId();
        }

        // 2. 不存在则通过 service 创建（自动处理 level/ancestors）
        StockPlate plate = new StockPlate();
        plate.setPlateName(plateName);
        plate.setPlateType(plateType);
        plate.setRemark(thsCode); // remark 存同花顺代码
        plate.setCreateBy("system");
        // parentId 不设或设0，由 service 按一级板块处理

        int rows = stockPlateService.insertPlate(plate);
        if (rows > 0 && plate.getId() != null)
        {
            log.info("创建板块: id={}, name={}, type={}, thsCode={}",
                    plate.getId(), plateName, plateType, thsCode);
            return plate.getId();
        }
        else
        {
            throw new RuntimeException("创建板块失败: " + plateName);
        }
    }

    // ==================== 股票持久化 ====================

    /**
     * 从排名数据中持久化股票记录
     * 已存在的更新名称，不存在的自动创建
     *
     * @param items 板块排名中的股票列表
     * @return 对应的 stock 表 ID 列表
     */
    private List<Long> persistStocks(List<BlockRankResponse.BlockRankItem> items)
    {
        // 1. 提取所有股票代码
        List<String> stockCodes = items.stream()
                .map(BlockRankResponse.BlockRankItem::getStockCode)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (stockCodes.isEmpty())
        {
            return Collections.emptyList();
        }

        // 2. 批量查询已存在的股票
        List<Stock> existingStocks = stockPlateMapper.selectStocksByCodeBatch(stockCodes);
        Map<String, Stock> existingMap = existingStocks.stream()
                .collect(Collectors.toMap(Stock::getStockCode, s -> s, (a, b) -> a));

        // 3. 构建 code → name 映射
        Map<String, String> codeNameMap = items.stream()
                .filter(i -> i.getStockCode() != null && i.getStockName() != null)
                .collect(Collectors.toMap(
                        BlockRankResponse.BlockRankItem::getStockCode,
                        BlockRankResponse.BlockRankItem::getStockName,
                        (a, b) -> a));

        // 4. 分类处理
        List<Stock> newStocks = new ArrayList<>();
        List<Stock> updateStocks = new ArrayList<>();
        Map<String, Long> codeIdMap = new LinkedHashMap<>();

        for (String code : stockCodes)
        {
            String name = codeNameMap.get(code);
            String market = inferMarket(code);

            if (existingMap.containsKey(code))
            {
                Stock s = existingMap.get(code);
                // 更新名称（同花顺的名称可能更准确）
                if (name != null && !name.equals(s.getStockName()))
                {
                    s.setStockName(name);
                    updateStocks.add(s);
                }
                // 补充市场
                if (s.getMarket() == null && market != null)
                {
                    s.setMarket(market);
                    updateStocks.add(s);
                }
                codeIdMap.put(code, s.getId());
            }
            else
            {
                if (autoCreateStock)
                {
                    Stock s = new Stock();
                    s.setStockCode(code);
                    s.setStockName(name);
                    s.setMarket(market);
                    s.setCreateBy("system");
                    newStocks.add(s);
                }
                else
                {
                    log.debug("股票不在库中且未开启自动创建: {} {}", code, name);
                }
            }
        }

        // 5. 批量插入新股票
        if (!newStocks.isEmpty())
        {
            stockMapper.batchInsertListIgnoreSame(newStocks);
            log.info("新增股票 {} 只", newStocks.size());

            // 重新查询获取 ID
            List<String> newCodes = newStocks.stream()
                    .map(Stock::getStockCode)
                    .collect(Collectors.toList());
            List<Stock> inserted = stockPlateMapper.selectStocksByCodeBatch(newCodes);
            for (Stock s : inserted)
            {
                codeIdMap.put(s.getStockCode(), s.getId());
            }
            // 仍有未拿到 ID 的（可能重复被忽略），留作日志
            for (Stock s : newStocks)
            {
                codeIdMap.putIfAbsent(s.getStockCode(), -1L);
            }
        }

        // 6. 批量更新已有股票名称
        if (!updateStocks.isEmpty())
        {
            for (Stock s : updateStocks)
            {
                stockMapper.updateStock(s);
            }
            log.debug("更新股票名称 {} 只", updateStocks.size());
        }

        // 7. 添加已存在但未更新的股票
        for (Stock s : existingStocks)
        {
            codeIdMap.putIfAbsent(s.getStockCode(), s.getId());
        }

        // 8. 返回有效的 stock_id 列表
        List<Long> result = new ArrayList<>();
        for (String code : stockCodes)
        {
            Long id = codeIdMap.get(code);
            if (id != null && id > 0)
            {
                result.add(id);
            }
        }

        log.info("股票处理: 已有{}只, 新增{}只, 更新{}只, 有效关联{}只",
                existingStocks.size(), newStocks.size(), updateStocks.size(), result.size());

        return result;
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
     * 同步板块-股票关联（通过 IStockPlateService 自动同步 industry_name）
     *
     * @param plateId  板块ID
     * @param stockIds 股票ID列表
     * @return 保存的关联数量
     */
    private int syncPlateStockAssociations(Long plateId, List<Long> stockIds)
    {
        if (plateId == null || stockIds.isEmpty())
        {
            return 0;
        }

        if (fullReplace)
        {
            // 全量替换：先查出原有关联，通过 service 删除（触发 industry_name 清理）
            List<Stock> oldStocks = stockPlateMapper.selectPlateStocksByPlateId(plateId);
            if (!oldStocks.isEmpty())
            {
                Long[] oldIds = oldStocks.stream()
                        .map(Stock::getId)
                        .toArray(Long[]::new);
                stockPlateService.deletePlateStocks(plateId, oldIds);
                log.debug("清空板块 [{}] 原有 {} 条关联（含 industry_name 清理）",
                        plateId, oldIds.length);
            }
        }

        // 通过 service 批量插入（触发 industry_name 填充）
        Long[] stockIdArray = stockIds.toArray(new Long[0]);
        int rows = stockPlateService.insertPlateStocks(plateId, stockIdArray);

        log.info("板块 [{}] 关联同步完成: {} 只股票, 模式={}, industry_name 已同步",
                plateId, stockIds.size(), fullReplace ? "全量替换" : "增量追加");
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

    // ==================== 参数解析 ====================

    private List<SyncConfig> parseParams(String params)
    {
        if (params == null || params.trim().isEmpty())
        {
            return buildDefaultConfigs();
        }

        String trimmed = params.trim();

        // 单个板块代码 "881145"
        if (trimmed.matches("\\d+"))
        {
            PlateConfig pc = DEFAULT_PLATES.getOrDefault(trimmed,
                    new PlateConfig("板块" + trimmed, "CONCEPT"));
            return Collections.singletonList(
                    new SyncConfig(trimmed, DEFAULT_SORT, pc.type));
        }

        // plateCode:sortField "881145:199112"
        if (trimmed.matches("\\d+:\\d+"))
        {
            String[] parts = trimmed.split(":");
            PlateConfig pc = DEFAULT_PLATES.getOrDefault(parts[0],
                    new PlateConfig("板块" + parts[0], "CONCEPT"));
            return Collections.singletonList(
                    new SyncConfig(parts[0], parts[1], pc.type));
        }

        // 多个板块（逗号分隔）"881145,881121,881126"
        if (trimmed.matches("[\\d,\\s]+"))
        {
            List<SyncConfig> configs = new ArrayList<>();
            for (String code : trimmed.split("[,\\s]+"))
            {
                code = code.trim();
                if (!code.isEmpty())
                {
                    PlateConfig pc = DEFAULT_PLATES.getOrDefault(code,
                            new PlateConfig("板块" + code, "CONCEPT"));
                    configs.add(new SyncConfig(code, DEFAULT_SORT, pc.type));
                }
            }
            return configs;
        }

        log.warn("参数格式无法识别: {}, 使用默认板块列表", params);
        return buildDefaultConfigs();
    }

    private List<SyncConfig> buildDefaultConfigs()
    {
        List<SyncConfig> configs = new ArrayList<>();
        for (Map.Entry<String, PlateConfig> entry : DEFAULT_PLATES.entrySet())
        {
            PlateConfig pc = entry.getValue();
            configs.add(new SyncConfig(entry.getKey(), DEFAULT_SORT, pc.type));
        }
        return configs;
    }

    // ==================== 内部类 ====================

    /** 板块配置 */
    private static class PlateConfig
    {
        final String name;
        final String type;  // INDUSTRY / CONCEPT

        PlateConfig(String name, String type)
        {
            this.name = name;
            this.type = type;
        }
    }

    /** 同步配置 */
    private static class SyncConfig
    {
        final String plateCode;
        final String sortField;
        final String plateType;

        SyncConfig(String plateCode, String sortField, String plateType)
        {
            this.plateCode = plateCode;
            this.sortField = sortField;
            this.plateType = plateType;
        }
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
