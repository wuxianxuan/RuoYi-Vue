package com.ruoyi.system.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.system.domain.stock.Stock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富全市场股票列表数据源
 *
 * 接口: https://push2.eastmoney.com/api/qt/clist/get
 * 用途：每周同步一次全市场代码/名称，补齐新上市股票（基础页已不允许手工新增）。
 *
 * @author ruoyi
 */
@Component
public class EastmoneyStockListDataSource
{
    private static final Logger log = LoggerFactory.getLogger(EastmoneyStockListDataSource.class);

    @Autowired
    private RestTemplate restTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${stock.list-sync.api-url:https://push2.eastmoney.com/api/qt/clist/get}")
    private String apiUrl;

    @Value("${stock.list-sync.page-size:5000}")
    private int pageSize;

    public List<Stock> fetchAll()
    {
        List<Stock> result = new ArrayList<>();
        int page = 1;
        int total = -1;
        int size = pageSize <= 0 ? 5000 : pageSize;

        while (true)
        {
            URI uri = UriComponentsBuilder.fromUriString(apiUrl)
                    .queryParam("pn", page)
                    .queryParam("pz", size)
                    .queryParam("po", 1)
                    .queryParam("np", 1)
                    .queryParam("fltt", 2)
                    .queryParam("invt", 2)
                    .queryParam("fid", "f3")
                    .queryParam("fs", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048")
                    .queryParam("fields", "f12,f14")
                    .build()
                    .encode()
                    .toUri();

            JsonNode root;
            try
            {
                String body = restTemplate.getForObject(uri, String.class);
                root = objectMapper.readTree(body);
            }
            catch (Exception e)
            {
                log.error("东方财富股票列表请求失败: page={}, error={}", page, e.getMessage());
                break;
            }

            JsonNode data = root.path("data");
            if (data.isMissingNode() || data.isNull())
            {
                break;
            }
            if (total < 0)
            {
                total = data.path("total").asInt(0);
            }
            JsonNode diff = data.path("diff");
            if (!diff.isArray() || diff.size() == 0)
            {
                break;
            }
            for (JsonNode item : diff)
            {
                String code = text(item, "f12");
                String name = text(item, "f14");
                if (code == null || name == null)
                {
                    continue;
                }
                Stock stock = new Stock();
                stock.setStockCode(code);
                stock.setStockName(name);
                stock.setMarket(inferMarket(code));
                result.add(stock);
            }

            if (result.size() >= total || diff.size() < size)
            {
                break;
            }
            page++;
        }

        log.info("东方财富股票列表拉取完成: 共 {} 只（接口总数 {}）", result.size(), total);
        return result;
    }

    private String inferMarket(String code)
    {
        if (code == null || code.length() < 6)
        {
            return null;
        }
        char first = code.charAt(0);
        if (first == '6')
        {
            return "SH";
        }
        if (first == '0' || first == '3')
        {
            return "SZ";
        }
        if (first == '9' || first == '8' || first == '4')
        {
            return "BJ";
        }
        return null;
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
}
