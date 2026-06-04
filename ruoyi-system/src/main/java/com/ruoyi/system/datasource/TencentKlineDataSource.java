package com.ruoyi.system.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.StockKline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯财经K线数据源
 * 使用接口: https://proxy.finance.qq.com/ifzqgtimg/appstock/app/kline/kline
 * 参数格式: param=sh600023,day,2024-01-01,2024-12-31,2000
 */
@Order(1)
@Component
@ConditionalOnProperty(name = "stock.kline.tencent.enabled", havingValue = "true", matchIfMissing = true)
public class TencentKlineDataSource implements KlineDataSource
{
    private static final Logger log = LoggerFactory.getLogger(TencentKlineDataSource.class);

    @Autowired
    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stock.kline.tencent.base-url:https://proxy.finance.qq.com}")
    private String baseUrl;

    @Override
    public String getName()
    {
        return "腾讯";
    }

    @Override
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
            log.error("腾讯K线请求失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 解析腾讯K线响应数据
     * 响应格式: {"code":0,"data":{"sh600023":{"day":[["日期","开盘","收盘","最高","最低","成交量"],...]}}}
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
            JsonNode dataNode = root.path("data").path(fullCode).path(typeKey);

            if (dataNode.isMissingNode())
            {
                log.warn("腾讯响应中未找到K线数据: path=data.{}.{}", fullCode, typeKey);
                return result;
            }

            if (!dataNode.isArray())
            {
                log.warn("腾讯K线数据格式异常，期望数组，实际: {}", dataNode.getNodeType());
                return result;
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
                    StockKline kline = new StockKline();
                    kline.setStockCode(stockCode);
                    kline.setKlineType(klineType);
                    kline.setTradeTime(sdf.parse(tradeDate));
                    kline.setOpenPrice(new BigDecimal(node.get(1).asText()));
                    kline.setClosePrice(new BigDecimal(node.get(2).asText()));
                    kline.setHighPrice(new BigDecimal(node.get(3).asText()));
                    kline.setLowPrice(new BigDecimal(node.get(4).asText()));
                    kline.setVolume(node.get(5).asLong());

                    // 成交额（第7个字段，可能不存在）
                    if (node.size() >= 7)
                    {
                        String amountStr = node.get(6).asText();
                        // 处理可能包含非数字字符的情况（如除权信息）
                        if (amountStr.matches("-?\\d+\\.?\\d*"))
                        {
                            kline.setAmount(new BigDecimal(amountStr));
                        }
                        else
                        {
                            kline.setAmount(BigDecimal.ZERO);
                        }
                    }
                    else
                    {
                        kline.setAmount(BigDecimal.ZERO);
                    }

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