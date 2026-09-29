package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockDailyQuote;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.mapper.StockDailyQuoteMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockDayTradingService;
import com.ruoyi.system.service.IStockGroupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 适合做T选股实现
 *
 * 算法流程（对每只股票）：
 *   1. 取 stock_daily_quote 中最近 lookbackDays 个交易日；
 *   2. 排除创业板/科创板（无交易权限），排除 ST/*ST 股票；
 *   3. 对每只股票取近N日「最小日振幅」(amplitude) 以及 换手率(turnoverRate)/成交额(turnoverAmount)/量比(volumeRatio) 的
 *      平均值，有效快照天数 < minKlines 的股票直接过滤；
 *   4. 最小日振幅及三类均值均达到阈值（振幅大、换手率高、成交额大、量比高）即命中；
 *   5. 命中股票生成一个新分组（分组名含日期）。
 *
 * @author ruoyi
 */
@Service
public class StockDayTradingServiceImpl implements IStockDayTradingService
{
    private static final Logger log = LoggerFactory.getLogger(StockDayTradingServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockDailyQuoteMapper stockDailyQuoteMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    /** 回看交易日数（近N日） */
    @Value("${stock.day-trading.lookback-days:5}")
    private int lookbackDays;

    /** 最少有效快照天数（不足则过滤） */
    @Value("${stock.day-trading.min-klines:3}")
    private int minKlines;

    /** 近N日平均振幅(%)下限 */
    @Value("${stock.day-trading.min-amplitude:3.0}")
    private BigDecimal minAmplitude;

    /** 近N日平均换手率(%)下限 */
    @Value("${stock.day-trading.min-turnover-rate:3.0}")
    private BigDecimal minTurnoverRate;

    /** 近N日平均成交额下限（亿） */
    @Value("${stock.day-trading.min-turnover-amount:1.0}")
    private BigDecimal minTurnoverAmountYi;

    /** 近N日平均量比下限 */
    @Value("${stock.day-trading.min-volume-ratio:1.0}")
    private BigDecimal minVolumeRatio;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.day-trading.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.day-trading.markets:SH,SZ}")
    private String markets;

    /** 排除的板块代码前缀（逗号分隔），默认排除创业板(300/301)、科创板(688/689) */
    @Value("${stock.day-trading.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    /** 一亿（元），用于把成交额阈值从「亿」换算为「元」 */
    private static final BigDecimal YI = new BigDecimal("100000000");

    @Override
    public void execute()
    {
        LocalDate today = LocalDate.now();
        log.info("========== 适合做T选股开始 ==========");
        log.info("参数：lookbackDays={} minKlines={} minAmplitude={} minTurnoverRate={} minTurnoverAmountYi={} minVolumeRatio={} excludeSt={} markets={} excludeCodePrefixes={}",
                lookbackDays, minKlines, minAmplitude, minTurnoverRate, minTurnoverAmountYi, minVolumeRatio, excludeSt, markets, excludeCodePrefixes);

        // 1. 查询候选股票基础信息
        List<Stock> stocks = stockMapper.selectStocksByMarkets(parseMarkets());
        if (stocks == null || stocks.isEmpty())
        {
            log.warn("指定市场下无股票，跳过选股");
            return;
        }

        List<String> excludePrefixes = parseExcludePrefixes();
        Map<String, Stock> stockMap = new LinkedHashMap<>();
        for (Stock s : stocks)
        {
            if (s.getStockCode() == null)
            {
                continue;
            }
            if (excludeSt && s.getStockName() != null && s.getStockName().toUpperCase().contains("ST"))
            {
                continue;
            }
            if (startsWithAny(s.getStockCode(), excludePrefixes))
            {
                continue;
            }
            stockMap.put(s.getStockCode(), s);
        }
        if (stockMap.isEmpty())
        {
            log.warn("过滤后无候选股票，跳过选股");
            return;
        }
        log.info("候选股票 {} 只（过滤掉 {} 只）", stockMap.size(), stocks.size() - stockMap.size());

        // 2. 取最近 lookbackDays 个交易日
        List<Date> tradeDates = stockDailyQuoteMapper.selectRecentTradeDates(lookbackDays);
        if (tradeDates == null || tradeDates.isEmpty())
        {
            log.warn("stock_daily_quote 无交易日期数据，跳过选股");
            return;
        }
        log.info("回看交易日 {} 个：{}", tradeDates.size(), tradeDates);

        // 3. 取这些交易日内的每日行情快照
        List<StockDailyQuote> quotes = stockDailyQuoteMapper.selectByTradeDates(tradeDates);
        if (quotes == null || quotes.isEmpty())
        {
            log.warn("回看交易日内无行情快照，跳过选股");
            return;
        }
        Map<String, List<StockDailyQuote>> quoteMap = quotes.stream()
                .filter(q -> q.getStockCode() != null)
                .collect(Collectors.groupingBy(StockDailyQuote::getStockCode));

        // 4. 逐只判定
        BigDecimal minTurnoverAmountYuan = minTurnoverAmountYi.multiply(YI);
        List<Stock> matched = new ArrayList<>();
        for (Stock s : stockMap.values())
        {
            if (matches(quoteMap.get(s.getStockCode()), minTurnoverAmountYuan))
            {
                matched.add(s);
            }
        }
        log.info("符合条件的股票 {} 只", matched.size());

        // 5. 生成分组（无命中则不生成）
        if (matched.isEmpty())
        {
            log.warn("无符合条件的股票，任务结束");
            return;
        }

        createGroup(matched, today);
        log.info("========== 适合做T选股完成 ==========");
    }

    /**
     * 判定单只股票是否满足「适合做T」条件
     */
    private boolean matches(List<StockDailyQuote> quotes, BigDecimal minTurnoverAmountYuan)
    {
        if (quotes == null || quotes.isEmpty())
        {
            return false;
        }
        // 有效快照天数不足则过滤
        if (quotes.size() < minKlines)
        {
            return false;
        }

        // 振幅取近N日「最小日振幅」（最差那天也要有足够波动，避免被某天大涨拉高均值），其余取平均值
        BigDecimal minDayAmplitude = min(quotes, StockDailyQuote::getAmplitude);
        BigDecimal avgTurnoverRate = avg(quotes, StockDailyQuote::getTurnoverRate);
        BigDecimal avgTurnoverAmount = avg(quotes, StockDailyQuote::getTurnoverAmount);
        BigDecimal avgVolumeRatio = avg(quotes, StockDailyQuote::getVolumeRatio);

        // 任一指标无有效值则视为不满足
        if (minDayAmplitude == null || avgTurnoverRate == null || avgTurnoverAmount == null || avgVolumeRatio == null)
        {
            return false;
        }

        return minDayAmplitude.compareTo(minAmplitude) >= 0
                && avgTurnoverRate.compareTo(minTurnoverRate) >= 0
                && avgTurnoverAmount.compareTo(minTurnoverAmountYuan) >= 0
                && avgVolumeRatio.compareTo(minVolumeRatio) >= 0;
    }

    /**
     * 计算非空指标的最小值，无有效值返回 null
     */
    private BigDecimal min(List<StockDailyQuote> quotes, Function<StockDailyQuote, BigDecimal> getter)
    {
        BigDecimal min = null;
        for (StockDailyQuote q : quotes)
        {
            BigDecimal v = getter.apply(q);
            if (v != null && (min == null || v.compareTo(min) < 0))
            {
                min = v;
            }
        }
        return min;
    }

    /**
     * 计算非空指标的平均值，无有效值返回 null
     */
    private BigDecimal avg(List<StockDailyQuote> quotes, Function<StockDailyQuote, BigDecimal> getter)
    {
        BigDecimal sum = BigDecimal.ZERO;
        int count = 0;
        for (StockDailyQuote q : quotes)
        {
            BigDecimal v = getter.apply(q);
            if (v != null)
            {
                sum = sum.add(v);
                count++;
            }
        }
        if (count == 0)
        {
            return null;
        }
        return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /**
     * 生成适合做T分组
     */
    private void createGroup(List<Stock> matched, LocalDate today)
    {
        String groupName = "适合做T选股_" + today;

        // 删除同名旧分组后重新生成，保证当日结果唯一
        StockGroup query = new StockGroup();
        query.setGroupName(groupName);
        List<StockGroup> existing = stockGroupService.selectGroupList(query);
        if (existing != null)
        {
            Long[] ids = existing.stream()
                    .filter(g -> groupName.equals(g.getGroupName()) && g.getId() != null)
                    .map(StockGroup::getId)
                    .toArray(Long[]::new);
            if (ids.length > 0)
            {
                stockGroupService.deleteGroupByIds(ids);
                log.info("删除同名旧分组 [{}] {} 个，重新生成", groupName, ids.length);
            }
        }

        StockGroup group = new StockGroup();
        group.setGroupName(groupName);
        group.setStockCodes(matched.stream().map(Stock::getStockCode).toArray(String[]::new));
        group.setCreateBy("system");
        group.setRemark(String.format("近%d日最小振幅≥%s%%、平均换手率≥%s%%、平均成交额≥%s亿、平均量比≥%s（命中%d只）",
                lookbackDays, minAmplitude, minTurnoverRate, minTurnoverAmountYi, minVolumeRatio, matched.size()));

        stockGroupService.insertGroup(group);

        log.info("分组 [{}] 命中 {} 只", groupName, matched.size());
        for (Stock s : matched)
        {
            log.info("    {} {}", s.getStockCode(), s.getStockName());
        }
    }

    /**
     * 解析市场列表配置
     */
    private List<String> parseMarkets()
    {
        if (markets == null || markets.trim().isEmpty())
        {
            return Arrays.asList("SH", "SZ");
        }
        return Arrays.stream(markets.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 解析排除板块代码前缀配置
     */
    private List<String> parseExcludePrefixes()
    {
        if (excludeCodePrefixes == null || excludeCodePrefixes.trim().isEmpty())
        {
            return Arrays.asList("300", "301", "688", "689");
        }
        return Arrays.stream(excludeCodePrefixes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * 判断股票代码是否命中任一排除前缀（创业板/科创板等无交易权限板块）
     */
    private boolean startsWithAny(String code, List<String> prefixes)
    {
        for (String prefix : prefixes)
        {
            if (code.startsWith(prefix))
            {
                return true;
            }
        }
        return false;
    }
}
