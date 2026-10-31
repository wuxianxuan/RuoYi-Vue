package com.ruoyi.system.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.StockLockupSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富限售解禁数据源
 *
 * 接口: https://datacenter-web.eastmoney.com/api/data/v1/get
 * reportName: RPT_LIFT_STAGE（限售解禁-解禁详情一览）
 *
 * 字段（真实上游 key）：
 *   SECURITY_CODE / SECURITY_NAME_ABBR / FREE_DATE /
 *   CURRENT_FREE_SHARES（万股）/ LIFT_MARKET_CAP（万元）/
 *   FREE_RATIO（占流通市值比例）/ TOTAL_RATIO / FREE_SHARES_TYPE / BATCH_HOLDER_NUM
 *
 * @author ruoyi
 */
@Component
public class EastmoneyLockupDataSource
{
    private static final Logger log = LoggerFactory.getLogger(EastmoneyLockupDataSource.class);

    private static final String COLUMNS = "SECURITY_CODE,SECURITY_NAME_ABBR,FREE_DATE,"
            + "CURRENT_FREE_SHARES,ABLE_FREE_SHARES,LIFT_MARKET_CAP,FREE_RATIO,"
            + "TOTAL_RATIO,FREE_SHARES_TYPE,BATCH_HOLDER_NUM";

    private static final BigDecimal WAN = new BigDecimal("10000");

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stock.lockup.api-url:https://datacenter-web.eastmoney.com/api/data/v1/get}")
    private String apiUrl;

    @Value("${stock.lockup.page-size:500}")
    private int pageSize;

    /**
     * 拉取指定日期区间的解禁记录（自动翻页）
     */
    public List<StockLockupSchedule> fetch(LocalDate start, LocalDate end)
    {
        List<StockLockupSchedule> result = new ArrayList<>();
        int page = 1;
        int pages = 1;
        String filter = String.format("(FREE_DATE>='%s')(FREE_DATE<='%s')", start, end);

        do
        {
            URI uri = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("sortColumns", "FREE_DATE,CURRENT_FREE_SHARES")
                    .queryParam("sortTypes", "1,1")
                    .queryParam("pageSize", pageSize)
                    .queryParam("pageNumber", page)
                    .queryParam("reportName", "RPT_LIFT_STAGE")
                    .queryParam("columns", COLUMNS)
                    .queryParam("source", "WEB")
                    .queryParam("client", "WEB")
                    .queryParam("filter", filter)
                    .build()
                    .encode()
                    .toUri();

            String body;
            try
            {
                body = restTemplate.getForObject(uri, String.class);
            }
            catch (Exception e)
            {
                log.error("东方财富解禁接口请求失败: page={}, error={}", page, e.getMessage());
                break;
            }

            JsonNode root;
            try
            {
                root = objectMapper.readTree(body);
            }
            catch (Exception e)
            {
                log.error("东方财富解禁接口响应解析失败: page={}, error={}", page, e.getMessage());
                break;
            }

            if (!root.path("success").asBoolean(false))
            {
                log.warn("东方财富解禁接口返回失败: page={}, message={}", page, root.path("message").asText());
                break;
            }

            JsonNode resultNode = root.path("result");
            if (resultNode.isMissingNode() || resultNode.isNull())
            {
                break;
            }
            pages = resultNode.path("pages").asInt(1);
            JsonNode data = resultNode.path("data");
            if (data.isArray())
            {
                for (JsonNode item : data)
                {
                    StockLockupSchedule schedule = parse(item);
                    if (schedule != null)
                    {
                        result.add(schedule);
                    }
                }
            }
            page++;
        }
        while (page <= pages);

        log.info("东方财富解禁数据拉取完成: {} ~ {}, {} 条, {} 页", start, end, result.size(), pages);
        return result;
    }

    private StockLockupSchedule parse(JsonNode item)
    {
        String code = text(item, "SECURITY_CODE");
        String freeDateText = text(item, "FREE_DATE");
        if (code == null || freeDateText == null || freeDateText.length() < 10)
        {
            return null;
        }

        StockLockupSchedule schedule = new StockLockupSchedule();
        schedule.setStockCode(code);
        schedule.setStockName(text(item, "SECURITY_NAME_ABBR"));
        try
        {
            schedule.setFreeDate(java.sql.Date.valueOf(LocalDate.parse(freeDateText.substring(0, 10))));
        }
        catch (Exception e)
        {
            return null;
        }

        BigDecimal freeSharesWan = decimal(item, "CURRENT_FREE_SHARES");
        schedule.setFreeShares(freeSharesWan == null ? null : freeSharesWan.multiply(WAN));

        BigDecimal marketCapWan = decimal(item, "LIFT_MARKET_CAP");
        schedule.setLiftMarketCap(marketCapWan == null ? null : marketCapWan.multiply(WAN));

        schedule.setFreeRatio(decimal(item, "FREE_RATIO"));
        schedule.setTotalRatio(decimal(item, "TOTAL_RATIO"));
        schedule.setSharesType(text(item, "FREE_SHARES_TYPE"));
        schedule.setHolderNum(integer(item, "BATCH_HOLDER_NUM"));
        schedule.setSource("eastmoney");
        return schedule;
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull())
        {
            return null;
        }
        String text = value.asText();
        return text == null || text.isEmpty() ? null : text;
    }

    private BigDecimal decimal(JsonNode node, String field)
    {
        String text = text(node, field);
        if (text == null || "-".equals(text) || "--".equals(text))
        {
            return null;
        }
        try
        {
            return new BigDecimal(text);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    private Integer integer(JsonNode node, String field)
    {
        BigDecimal value = decimal(node, field);
        return value == null ? null : value.intValue();
    }
}
