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
}
