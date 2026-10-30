package com.ruoyi.system.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.StockKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯财经K线数据源
 * 使用接口: https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/kline
 * 参数格式: param=sh600023,day,2024-01-01,2024-12-31,2000
 */
@Component
public class TencentKlineDataSource
{
    private static final Logger log = LoggerFactory.getLogger(TencentKlineDataSource.class);

    @Autowired
    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stock.kline.tencent.base-url:https://proxy.finance.qq.com}")
    private String baseUrl;

    /** 分时（分钟K线）接口域名，与日K线接口不同 */
    @Value("${stock.kline.tencent.minute-base-url:https://ifzq.gtimg.cn}")
    private String minuteBaseUrl;

    public List<StockKline> fetchKline(String stockCode, String market, String klineType,
                                       String startDate, String endDate)
    {
        // 兼容两种市场标识: "1"或"SH"表示沪市, "0"或"SZ"表示深市
        String prefix;
        if ("1".equals(market) || "SH".equalsIgnoreCase(market)) {
            prefix = "sh";
        } else {
            prefix = "sz";
        }
        String fullCode = prefix + stockCode;
        String type = convertKlineType(klineType);

        // 构造参数: code,type,startDate,endDate,maxCount
        String start = (startDate != null && !startDate.isEmpty()) ? startDate : "";
        String end = (endDate != null && !endDate.isEmpty()) ? endDate : "";
        String param = fullCode + "," + type + "," + start + "," + end + ",2000";

        String url = baseUrl + "/ifzqgtimg/appstock/app/kline/kline?param=" + param;
        log.info("腾讯K线请求: {}", url);

        try
        {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return parseResponse(response.getBody(), fullCode, stockCode, klineType, startDate, endDate);
        }
        catch (Exception e)
        {
            log.error("腾讯K线请求失败: stockCode={}, error={}", stockCode, e.getMessage());
            throw new RuntimeException("腾讯K线请求失败: " + stockCode, e);
        }
    }

    /**
     * 解析腾讯K线响应数据
     * 响应格式: {"code":0,"data":{"sz000609":{"day":[["日期","开盘","收盘","最高","最低","成交量(手)"],...],
     *           "qt":{"sz000609":[...]}}}}
     *  - day 数组字段: [日期, 开盘, 收盘, 最高, 最低, 成交量(手)]
     *  - 成交量单位是"手"，落库统一 ×100 转"股"
     *  - 成交额：历史日用 平均价((开+收+高+低)/4) × 成交量(股) 近似；最新交易日用 qt 里的实际成交额
     */
    private List<StockKline> parseResponse(String body, String fullCode, String stockCode, String klineType,
                                           String startDate, String endDate)
    {
        List<StockKline> result = new ArrayList<>();
        if (body == null || body.isEmpty())
        {
            log.warn("腾讯K线响应内容为空");
            return result;
        }

        try
        {
            JsonNode root = objectMapper.readTree(body);

            // 检查返回码
            if (root.has("code") && root.get("code").asInt() != 0)
            {
                String msg = root.has("msg") ? root.get("msg").asText() : "未知错误";
                log.warn("腾讯API返回错误: code={}, msg={}", root.get("code").asInt(), msg);
                return result;
            }

            // 获取K线数据: data.{fullCode}.{klineType}
            String typeKey = convertKlineType(klineType);
            JsonNode stockNode = root.path("data").path(fullCode);
            JsonNode dataNode = stockNode.path(typeKey);

            if (dataNode.isMissingNode() || !dataNode.isArray())
            {
                log.warn("腾讯响应中未找到K线数据: path=data.{}.{}", fullCode, typeKey);
                return result;
            }

            // 提取 qt 里最新交易日的实际成交额（元）和日期，用于覆盖最新一日的 amount
            String qtDate = null;
            BigDecimal qtAmount = null;
            JsonNode qtNode = stockNode.path("qt").path(fullCode);
            if (qtNode.isArray() && qtNode.size() > 35)
            {
                // index 30: 时间 yyyyMMddHHmmss
                String timeStr = qtNode.get(30).asText();
                if (timeStr != null && timeStr.length() >= 8)
                {
                    qtDate = timeStr.substring(0, 8);
                }
                // index 35: "价格/成交量/成交额"，第三段为成交额(元)
                String priceVolAmt = qtNode.get(35).asText();
                if (priceVolAmt != null && !priceVolAmt.isEmpty())
                {
                    String[] parts = priceVolAmt.split("/");
                    if (parts.length >= 3)
                    {
                        try
                        {
                            qtAmount = new BigDecimal(parts[2]);
                        }
                        catch (NumberFormatException ignored) { }
                    }
                }
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            int parsedCount = 0;
            int filteredCount = 0;

            for (JsonNode node : dataNode)
            {
                if (!node.isArray() || node.size() < 6)
                {
                    log.debug("跳过无效K线数据节点: {}", node);
                    continue;
                }

                String tradeDate = node.get(0).asText();

                // 日期范围过滤
                if ((startDate != null && !startDate.isEmpty() && tradeDate.compareTo(startDate) < 0)
                        || (endDate != null && !endDate.isEmpty() && tradeDate.compareTo(endDate) > 0))
                {
                    filteredCount++;
                    continue;
                }

                try
                {
                    BigDecimal open = new BigDecimal(node.get(1).asText());
                    BigDecimal close = new BigDecimal(node.get(2).asText());
                    BigDecimal high = new BigDecimal(node.get(3).asText());
                    BigDecimal low = new BigDecimal(node.get(4).asText());
                    // 成交量：接口返回"手"，×100 转"股"
                    long volumeShares = new BigDecimal(node.get(5).asText())
                            .multiply(BigDecimal.valueOf(100)).longValue();

                    // 成交额：最新交易日用 qt 实际值，历史日用 平均价×成交量(股) 近似
                    BigDecimal amount;
                    String dayKey = tradeDate.replace("-", "");
                    if (qtDate != null && qtDate.equals(dayKey) && qtAmount != null)
                    {
                        amount = qtAmount;
                    }
                    else
                    {
                        BigDecimal avgPrice = open.add(close).add(high).add(low)
                                .divide(BigDecimal.valueOf(4), 4, RoundingMode.HALF_UP);
                        amount = avgPrice.multiply(BigDecimal.valueOf(volumeShares));
                    }

                    StockKline kline = new StockKline();
                    kline.setStockCode(stockCode);
                    kline.setKlineType(klineType);
                    kline.setTradeTime(sdf.parse(tradeDate));
                    kline.setOpenPrice(open);
                    kline.setClosePrice(close);
                    kline.setHighPrice(high);
                    kline.setLowPrice(low);
                    kline.setVolume(volumeShares);
                    kline.setAmount(amount);

                    result.add(kline);
                    parsedCount++;
                }
                catch (Exception e)
                {
                    log.debug("解析单条K线数据失败: date={}, error={}", tradeDate, e.getMessage());
                }
            }

            log.info("腾讯K线解析完成: 共{}条，日期过滤{}条，成功解析{}条",
                    dataNode.size(), filteredCount, parsedCount);
        }
        catch (Exception e)
        {
            log.error("解析腾讯K线数据失败", e);
        }

        return result;
    }

    /**
     * 转换K线类型为腾讯API格式
     * @param klineType D-日K, W-周K, M-月K, 其他数字为分钟K
     * @return 腾讯API对应的类型字符串
     */
    private String convertKlineType(String klineType)
    {
        if (klineType == null || klineType.isEmpty())
        {
            return "day";
        }

        switch (klineType)
        {
            case "D":
                return "day";
            case "W":
                return "week";
            case "M":
                return "month";
            default:
                // 分钟K线: 如 "5" -> "5min"
                if (klineType.matches("\\d+"))
                {
                    return klineType + "min";
                }
                return "day";
        }
    }

    /**
     * 拉取分时（分钟）K线。腾讯分时接口与日K接口不同：
     * 使用 https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=sz002093,m5,,320
     * 响应 data.{fullCode}.{type} 为数组，每根为 [时间(yyyyMMddHHmm), 开盘, 收盘, 最高, 最低, 成交量(手), {}, 成交额]。
     *
     * @param stockCode 股票代码
     * @param market    市场标识（"1"/"SH"=沪，"0"/"SZ"=深）
     * @param minuteType 分钟类型：m1/m5/m15/m30/m60
     * @param date      交易日期（yyyy-MM-dd），只保留该日期的分时bar；为空则返回全部
     * @return 分时K线列表（按时间升序）
     */
    public List<StockKline> fetchMinuteKline(String stockCode, String market, String minuteType, String date)
    {
        return fetchMinuteSnapshot(stockCode, market, minuteType, date).getBars();
    }

    /**
     * 拉取分时K线 + 同一次响应里的实时行情快照（换手率 / 量比）。
     *
     * 腾讯分时的每根 bar 只有 OHLCV 和成交额，换手率、量比在响应的 qt 实时行情块里：
     *   qt[30] = 时间(yyyyMMddHHmmss)
     *   qt[38] = 换手率(%)
     *   qt[49] = 量比
     * 该快照是"请求时刻"的最新值：盘中 14:45 调用拿到的就是 14:45 的量比/换手率，
     * 因此尾盘任务不再依赖收盘后才落库的 stock_daily_quote 快照。
     *
     * @param stockCode 股票代码
     * @param market    市场标识（"1"/"SH"=沪，"0"/"SZ"=深）
     * @param minuteType 分钟类型：m1/m5/m15/m30/m60
     * @param date      交易日期（yyyy-MM-dd），只保留该日期的分时bar；为空则返回全部
     * @return 分时K线 + 换手率/量比快照
     */
    public MinuteSnapshot fetchMinuteSnapshot(String stockCode, String market, String minuteType, String date)
    {
        return fetchMinuteSnapshot(stockCode, market, minuteType, date, 320);
    }

    /**
     * 拉取分时K线 + 实时行情快照，可指定拉取的 bar 数量
     *
     * @param bars 拉取多少根（1~320）。日常增量同步只需最近 2 天（96 根），响应体积约为 320 根的 1/3
     */
    public MinuteSnapshot fetchMinuteSnapshot(String stockCode, String market, String minuteType, String date, int bars)
    {
        String prefix;
        if ("1".equals(market) || "SH".equalsIgnoreCase(market)) {
            prefix = "sh";
        } else {
            prefix = "sz";
        }
        String fullCode = prefix + stockCode;
        String type = convertMinuteType(minuteType);
        int count = Math.max(1, Math.min(bars, 320));
        String param = fullCode + "," + type + ",," + count;
        String url = minuteBaseUrl + "/appstock/app/kline/mkline?param=" + param;
        log.debug("腾讯分时K线请求: {}", url);

        try
        {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            return parseMinuteSnapshot(response.getBody(), fullCode, stockCode, minuteType, date);
        }
        catch (Exception e)
        {
            log.error("腾讯分时K线请求失败: stockCode={}, error={}", stockCode, e.getMessage());
            throw new RuntimeException("腾讯分时K线请求失败: " + stockCode, e);
        }
    }

    /**
     * 解析腾讯分时K线响应（同时解析 qt 实时行情快照中的换手率/量比）
     */
    private MinuteSnapshot parseMinuteSnapshot(String body, String fullCode, String stockCode,
                                               String minuteType, String date)
    {
        List<StockKline> result = new ArrayList<>();
        Double turnoverRate = null;
        Double volumeRatio = null;
        String quoteTime = null;
        if (body == null || body.isEmpty())
        {
            return new MinuteSnapshot(result, null, null, null);
        }
        try
        {
            JsonNode root = objectMapper.readTree(body);
            if (root.has("code") && root.get("code").asInt() != 0)
            {
                log.warn("腾讯分时API返回错误: code={}", root.get("code").asInt());
                return new MinuteSnapshot(result, null, null, null);
            }
            JsonNode stockNode = root.path("data").path(fullCode);
            JsonNode dataNode = stockNode.path(convertMinuteType(minuteType));
            if (!dataNode.isArray())
            {
                return new MinuteSnapshot(result, null, null, null);
            }

            // 实时行情快照：qt 数组 index 30=时间，38=换手率(%)，49=量比
            JsonNode qtNode = stockNode.path("qt").path(fullCode);
            if (qtNode.isArray())
            {
                if (qtNode.size() > 38)
                {
                    turnoverRate = toDoubleOrNull(qtNode.get(38));
                }
                if (qtNode.size() > 49)
                {
                    volumeRatio = toDoubleOrNull(qtNode.get(49));
                }
                if (qtNode.size() > 30)
                {
                    quoteTime = qtNode.get(30).asText(null);
                }
                // 防御：接口字段位置若变化，异常值直接丢弃，避免把别的字段当成量比/换手率
                if (turnoverRate != null && (turnoverRate < 0 || turnoverRate > 500))
                {
                    turnoverRate = null;
                }
                if (volumeRatio != null && (volumeRatio < 0 || volumeRatio > 500))
                {
                    volumeRatio = null;
                }
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
            String datePrefix = (date == null || date.isEmpty()) ? null : date.replace("-", "");
            for (JsonNode node : dataNode)
            {
                if (!node.isArray() || node.size() < 6)
                {
                    continue;
                }
                String time = node.get(0).asText();
                if (datePrefix != null && !time.startsWith(datePrefix))
                {
                    continue;
                }
                try
                {
                    StockKline kline = new StockKline();
                    kline.setStockCode(stockCode);
                    kline.setKlineType(minuteType);
                    kline.setTradeTime(sdf.parse(time));
                    kline.setOpenPrice(new BigDecimal(node.get(1).asText()));
                    kline.setClosePrice(new BigDecimal(node.get(2).asText()));
                    kline.setHighPrice(new BigDecimal(node.get(3).asText()));
                    kline.setLowPrice(new BigDecimal(node.get(4).asText()));
                    if (node.size() > 5 && node.get(5).asText() != null && !node.get(5).asText().isEmpty())
                    {
                        kline.setVolume(new BigDecimal(node.get(5).asText()).multiply(BigDecimal.valueOf(100)).longValue());
                    }
                    result.add(kline);
                }
                catch (Exception e)
                {
                    log.debug("解析分时K线失败: time={}, error={}", time, e.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            log.error("解析腾讯分时K线数据失败", e);
        }
        return new MinuteSnapshot(result, turnoverRate, volumeRatio, quoteTime);
    }

    private Double toDoubleOrNull(JsonNode node)
    {
        if (node == null || node.isNull())
        {
            return null;
        }
        String text = node.asText();
        if (text == null || text.isEmpty() || "--".equals(text))
        {
            return null;
        }
        try
        {
            return new BigDecimal(text).doubleValue();
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    /**
     * 分时K线 + 实时行情快照（换手率/量比/快照时间）
     */
    public static class MinuteSnapshot
    {
        private final List<StockKline> bars;
        private final Double turnoverRate;
        private final Double volumeRatio;
        private final String quoteTime;

        public MinuteSnapshot(List<StockKline> bars, Double turnoverRate, Double volumeRatio, String quoteTime)
        {
            this.bars = bars;
            this.turnoverRate = turnoverRate;
            this.volumeRatio = volumeRatio;
            this.quoteTime = quoteTime;
        }

        public List<StockKline> getBars()
        {
            return bars;
        }

        public Double getTurnoverRate()
        {
            return turnoverRate;
        }

        public Double getVolumeRatio()
        {
            return volumeRatio;
        }

        public String getQuoteTime()
        {
            return quoteTime;
        }
    }

    /**
     * 转换分钟类型为腾讯分时接口格式："5" -> "m5"，"m5" -> "m5"
     */
    private String convertMinuteType(String minuteType)
    {
        if (minuteType == null || minuteType.isEmpty())
        {
            return "m5";
        }
        if (minuteType.startsWith("m"))
        {
            return minuteType;
        }
        if (minuteType.matches("\\d+"))
        {
            return "m" + minuteType;
        }
        return "m5";
    }
}
