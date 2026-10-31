package com.ruoyi.system.datasource;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockQuoteSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 腾讯批量行情数据源
 *
 * 接口: https://qt.gtimg.cn/q=sh600000,sz000001,bj920000
 *  - 一次可请求多只，返回 GBK 编码的 v_xxx="字段~字段~..." 文本
 *  - 覆盖沪/深/北，字段包含现价、涨跌幅、换手、量比、振幅、成交额、流通市值、PE
 *  - 不提供「涨速」，该字段保持 null
 *
 * 字段下标已用真实响应验证（2026-10）：
 *   1 名称 / 3 现价 / 30 时间 / 31 涨跌额 / 32 涨跌幅 / 38 换手率 / 39 PE /
 *   43 振幅 / 44 流通市值(亿) / 49 量比 / 72 流通股本(股)；35 为「价/量/额(元)」
 *
 * @author ruoyi
 */
@Component
public class TencentQuoteDataSource
{
    private static final Logger log = LoggerFactory.getLogger(TencentQuoteDataSource.class);

    private static final Pattern LINE_PATTERN = Pattern.compile("^v_([a-z]{2})(\\d{6})=\"(.*)\"$");

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final BigDecimal YI = new BigDecimal("100000000");

    private static final BigDecimal WAN = new BigDecimal("10000");

    @Autowired
    private RestTemplate restTemplate;

    /** 批量行情地址前缀（末尾带 ?q=） */
    @Value("${stock.quote.tencent.batch-url:https://qt.gtimg.cn/q=}")
    private String batchUrl;

    /** 每批请求的股票数量 */
    @Value("${stock.quote.tencent.batch-size:60}")
    private int batchSize;

    /** 单批失败重试次数 */
    private static final int MAX_RETRY = 2;

    /**
     * 批量拉取行情快照
     *
     * @param stocks 股票列表（只使用 stockCode / market 构造腾讯代码）
     * @return 解析成功的快照
     */
    public List<StockQuoteSnapshot> fetchQuotes(List<Stock> stocks)
    {
        List<StockQuoteSnapshot> result = new ArrayList<>();
        if (stocks == null || stocks.isEmpty())
        {
            return result;
        }

        List<String> fullCodes = new ArrayList<>(stocks.size());
        for (Stock stock : stocks)
        {
            String fullCode = toFullCode(stock);
            if (fullCode != null)
            {
                fullCodes.add(fullCode);
            }
        }

        int size = batchSize <= 0 ? 60 : batchSize;
        for (int i = 0; i < fullCodes.size(); i += size)
        {
            List<String> batch = fullCodes.subList(i, Math.min(i + size, fullCodes.size()));
            result.addAll(fetchBatchWithRetry(batch));
        }
        return result;
    }

    private List<StockQuoteSnapshot> fetchBatchWithRetry(List<String> batch)
    {
        for (int attempt = 0; attempt <= MAX_RETRY; attempt++)
        {
            try
            {
                return fetchBatch(batch);
            }
            catch (Exception e)
            {
                if (attempt >= MAX_RETRY)
                {
                    log.error("腾讯行情批次失败（{} 只）: {}", batch.size(), e.getMessage());
                    return new ArrayList<>();
                }
                try
                {
                    Thread.sleep(300L * (attempt + 1));
                }
                catch (InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return new ArrayList<>();
                }
            }
        }
        return new ArrayList<>();
    }

    private List<StockQuoteSnapshot> fetchBatch(List<String> batch)
    {
        String url = batchUrl + String.join(",", batch);
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, null, byte[].class);
        byte[] body = response.getBody();
        if (body == null || body.length == 0)
        {
            return new ArrayList<>();
        }
        // 腾讯接口返回 GBK/GB18030，不能按 UTF-8 解码
        String text = new String(body, Charset.forName("GB18030"));
        List<StockQuoteSnapshot> list = new ArrayList<>();
        for (String raw : text.split(";"))
        {
            String line = raw.trim();
            if (line.isEmpty())
            {
                continue;
            }
            Matcher matcher = LINE_PATTERN.matcher(line);
            if (!matcher.matches())
            {
                continue;
            }
            StockQuoteSnapshot snapshot = parse(matcher.group(1), matcher.group(2), matcher.group(3));
            if (snapshot != null)
            {
                list.add(snapshot);
            }
        }
        log.debug("腾讯行情批次完成: 请求 {} 只, 解析 {} 只", batch.size(), list.size());
        return list;
    }

    private StockQuoteSnapshot parse(String prefix, String code, String payload)
    {
        if (payload == null || payload.isEmpty())
        {
            return null;
        }
        String[] f = payload.split("~", -1);
        if (f.length < 50)
        {
            return null;
        }
        BigDecimal price = decimal(f, 3);
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0)
        {
            // 停牌/无效行情不覆盖已有快照
            return null;
        }

        StockQuoteSnapshot s = new StockQuoteSnapshot();
        s.setStockCode(code);
        s.setStockName(text(f, 1));
        s.setMarket(prefix.toUpperCase());
        s.setTradeDate(parseDate(text(f, 30)));
        s.setCurrentPrice(price);
        s.setPriceChange(decimal(f, 31));
        s.setChangeRate(decimal(f, 32));
        s.setTurnoverRate(decimal(f, 38));
        s.setPeRatio(decimal(f, 39));
        s.setAmplitude(decimal(f, 43));
        s.setVolumeRatio(decimal(f, 49));

        BigDecimal circCapYi = decimal(f, 44);
        if (circCapYi != null)
        {
            s.setCircMarketCap(circCapYi.multiply(YI));
        }

        BigDecimal shares = decimal(f, 72);
        if (shares == null && s.getCircMarketCap() != null)
        {
            shares = s.getCircMarketCap().divide(price, 0, RoundingMode.HALF_UP);
        }
        s.setCirculatingShares(shares);

        BigDecimal amount = amount(f, 35, 37);
        s.setTurnoverAmount(amount);
        return s;
    }

    /**
     * 成交额：优先取 35 的「价/量/额(元)」第三段，取不到用 37（万元）换算
     */
    private BigDecimal amount(String[] f, int priceVolAmtIndex, int amountWanIndex)
    {
        String pva = text(f, priceVolAmtIndex);
        if (pva != null)
        {
            String[] parts = pva.split("/");
            if (parts.length >= 3)
            {
                BigDecimal amount = parse(parts[2]);
                if (amount != null)
                {
                    return amount;
                }
            }
        }
        BigDecimal wan = decimal(f, amountWanIndex);
        return wan == null ? null : wan.multiply(WAN);
    }

    private LocalDate parseDate(String time)
    {
        if (time != null && time.length() >= 8)
        {
            try
            {
                return LocalDate.parse(time.substring(0, 8), DATE_FORMAT);
            }
            catch (Exception ignored)
            {
            }
        }
        return LocalDate.now();
    }

    private String toFullCode(Stock stock)
    {
        if (stock == null || stock.getStockCode() == null)
        {
            return null;
        }
        String market = stock.getMarket();
        String prefix;
        if ("SH".equalsIgnoreCase(market))
        {
            prefix = "sh";
        }
        else if ("SZ".equalsIgnoreCase(market))
        {
            prefix = "sz";
        }
        else if ("BJ".equalsIgnoreCase(market))
        {
            prefix = "bj";
        }
        else
        {
            return null;
        }
        return prefix + stock.getStockCode();
    }

    private String text(String[] f, int index)
    {
        if (index >= f.length)
        {
            return null;
        }
        String value = f[index];
        return value == null || value.isEmpty() ? null : value.trim();
    }

    private BigDecimal decimal(String[] f, int index)
    {
        return parse(text(f, index));
    }

    private BigDecimal parse(String value)
    {
        if (value == null || value.isEmpty() || "-".equals(value) || "--".equals(value))
        {
            return null;
        }
        try
        {
            return new BigDecimal(value.trim());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
