package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockRisingLow;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockRisingLowMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 趋势上涨选股实现
 *
 * 算法流程（对每只股票）：
 *   1. 以截止日期（入参，为空则当天）往前回看 lookbackDays 个日历日的日K线；
 *   2. 排除创业板/科创板（无交易权限），排除 ST/*ST 股票；
 *   3. 找到「收盘价最低」的那天作为起点，切片 [最低日, 最新日]；
 *      最低日到最新日合计需至少 minLowDays 根K线（含端点），否则过滤；
 *   4. 切片链上任一天都不得为涨停（相对前收涨幅 >= limitUpThreshold%），涨停说明已爆发；
 *   5. 上涨切片内任一天相对前收涨幅都不得低于 minDailyGain%（过滤日涨幅过低的数据）；
 *   6. 判定收盘价链逐步上涨：
 *      - 整体上涨：末个收盘价 > 首个收盘价；
 *      - 偏离次数 <= ceil(收盘价数量 * maxDeviationRatio)；
 *      - 偏离程度（偏离累计跌幅 / (最高点-最低点)）<= maxDeviationDegree。
 * 收盘价数量不足 minKlines 的股票直接过滤。
 *
 * 命中股票累加进固定分组（去重），并记录每只命中股票的最低点日期（同股票同日期仅记录一条）。
 *
 * @author ruoyi
 */
@Service
public class StockRisingTrendServiceImpl implements IStockRisingTrendService
{
    private static final Logger log = LoggerFactory.getLogger(StockRisingTrendServiceImpl.class);

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private StockKlineMapper stockKlineMapper;

    @Autowired
    private IStockGroupService stockGroupService;

    @Autowired
    private StockRisingLowMapper stockRisingLowMapper;

    /** 回看日历天数（a） */
    @Value("${stock.rising-trend.lookback-days:30}")
    private int lookbackDays;

    /** 最少K线数（用于收盘价数量下限判定：收盘价数量 < minKlines 则过滤） */
    @Value("${stock.rising-trend.min-klines:15}")
    private int minKlines;

    /** 是否要求整体上涨（末个收盘价 > 首个收盘价） */
    @Value("${stock.rising-trend.require-overall-up:true}")
    private boolean requireOverallUp;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.rising-trend.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.rising-trend.markets:SH,SZ}")
    private String markets;

    /** 目标分组名称（固定名，不随日期变化；首次执行自动创建，之后每次执行将新命中股票累加进该分组） */
    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String groupName;

    /** 最低点到最新点的最少K线根数（含端点，合计 < 该值则过滤，确保上涨趋势已确立） */
    @Value("${stock.rising-trend.min-low-days:5}")
    private int minLowDays;

    /** 偏离数量上限比例：整个收盘价链上偏离（下跌）次数 <= ceil(收盘价数量 * 该值) */
    @Value("${stock.rising-trend.max-deviation-ratio:0.2}")
    private double maxDeviationRatio;

    /** 偏离程度上限比例：偏离累计跌幅 / (最高点 - 最低点) <= 该值 */
    @Value("${stock.rising-trend.max-deviation-degree:0.2}")
    private double maxDeviationDegree;

    /** 涨停判定阈值(%)：切片链上任一天相对前收涨幅 >= 该值视为涨停，直接过滤 */
    @Value("${stock.rising-trend.limit-up-threshold:9.8}")
    private double limitUpThreshold;

    /** 单日最小涨幅下限(%)：上涨切片内任一天相对前收涨幅 < 该值则过滤（用于过滤日涨幅过低的数据） */
    @Value("${stock.rising-trend.min-daily-gain:0.5}")
    private double minDailyGain;

    /** 排除的板块代码前缀（逗号分隔），默认排除创业板(300/301)、科创板(688/689) */
    @Value("${stock.rising-trend.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    @Override
    public void execute(String date)
    {
        // 解析截止日期：入参为空则默认当天
        LocalDate endDate = parseDate(date);
        LocalDate startDate = endDate.minusDays(lookbackDays);
        log.info("========== 趋势上涨选股开始 ==========");
        log.info("参数：endDate={} startDate={} lookbackDays={} minKlines={} minLowDays={} requireOverallUp={} excludeSt={} markets={} excludeCodePrefixes={} maxDeviationRatio={} maxDeviationDegree={} limitUpThreshold={} minDailyGain={} groupName={}",
                endDate, startDate, lookbackDays, minKlines, minLowDays, requireOverallUp, excludeSt, markets, excludeCodePrefixes, maxDeviationRatio, maxDeviationDegree, limitUpThreshold, minDailyGain, groupName);

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

        // 2. 批量拉取日K线
        List<String> codes = new ArrayList<>(stockMap.keySet());
        List<StockKline> allKlines = stockKlineMapper.selectKlineByCodesAndDays(codes, "D", startDate.toString(), endDate.toString());
        log.info("K线数据总量: {}", allKlines != null ? allKlines.size() : 0);

        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));

        // 3. 逐只判定
        List<Stock> matched = new ArrayList<>();
        List<StockRisingLow> lowRecords = new ArrayList<>();
        for (Stock s : stockMap.values())
        {
            TrendResult result = analyze(klineMap.get(s.getStockCode()));
            if (result.rising)
            {
                matched.add(s);
                if (result.lowDate != null)
                {
                    StockRisingLow record = new StockRisingLow();
                    record.setStockCode(s.getStockCode());
                    record.setLowDate(result.lowDate);
                    lowRecords.add(record);
                    log.info("    命中 {} {}，最低点日期 {}", s.getStockCode(), s.getStockName(), result.lowDate);
                }
            }
        }
        log.info("符合条件的股票 {} 只", matched.size());

        // 4. 生成分组（无命中则不生成）
        if (matched.isEmpty())
        {
            log.warn("无符合条件的股票，任务结束");
            return;
        }

        // 记录最低点日期（同股票同日期仅保留一条）
        if (!lowRecords.isEmpty())
        {
            stockRisingLowMapper.insertIgnoreBatch(lowRecords);
            log.info("记录最低点日期 {} 条", lowRecords.size());
        }

        // 累加进固定分组（去重）
        mergeIntoGroup(matched, endDate);
        log.info("========== 趋势上涨选股完成 ==========");
    }

    /**
     * 判定一组日K线是否仍处于「逐步上涨」形态
     */
    @Override
    public boolean isRisingTrend(List<StockKline> klines)
    {
        return analyze(klines).rising;
    }

    /**
     * 分析一组日K线是否处于「逐步上涨」形态，命中时返回最低点日期
     */
    private TrendResult analyze(List<StockKline> klines)
    {
        TrendResult result = new TrendResult();
        if (klines == null || klines.isEmpty())
        {
            return result;
        }

        // 按交易日升序排序（过滤 tradeTime 为空的脏数据），提取有效收盘价及其交易日
        List<StockKline> list = klines.stream()
                .filter(k -> k.getTradeTime() != null)
                .sorted(Comparator.comparing(StockKline::getTradeTime))
                .collect(Collectors.toList());

        List<Double> closes = new ArrayList<>();
        List<String> closeDates = new ArrayList<>();
        for (StockKline kl : list)
        {
            if (kl.getClosePrice() != null)
            {
                closes.add(kl.getClosePrice().doubleValue());
                closeDates.add(toDateString(kl.getTradeTime()));
            }
        }
        // 历史数据不足 minKlines 直接过滤
        if (closes.size() < minKlines)
        {
            return result;
        }

        int closeCount = closes.size();

        // 找到收盘价最低的那天，作为起点切片到最新
        int minIdx = 0;
        for (int i = 1; i < closeCount; i++)
        {
            if (closes.get(i) < closes.get(minIdx))
            {
                minIdx = i;
            }
        }
        if (minIdx >= closeCount - 1)
        {
            // 最低点就是最新一天，无上升空间
            return result;
        }
        // 最低点到最新点合计需至少 minLowDays 根K线（含端点），否则上涨趋势尚未确立
        if (closeCount - minIdx < minLowDays)
        {
            return result;
        }

        // 从最低点到最新点的整个链上，任一天都不得为涨停（相对前收涨幅 >= limitUpThreshold%），涨停说明已爆发
        for (int i = minIdx; i < closeCount; i++)
        {
            if (i == 0)
            {
                continue;
            }
            double prevClose = closes.get(i - 1);
            double curClose = closes.get(i);
            if (prevClose > 0 && (curClose - prevClose) / prevClose * 100 >= limitUpThreshold)
            {
                return result;
            }
        }

        // 单日最小涨幅下限：上涨切片内任一天相对前收涨幅 < minDailyGain 则过滤（过滤日涨幅过低的数据）。
        // 从最低点次日开始计算，避免最低点当天（本身就是下跌日）被误杀。
        for (int i = minIdx + 1; i < closeCount; i++)
        {
            double prevClose = closes.get(i - 1);
            double curClose = closes.get(i);
            if (prevClose > 0 && (curClose - prevClose) / prevClose * 100 < minDailyGain)
            {
                return result;
            }
        }

        // 收盘价切片作为节点链（原按 pointsPerNode 取均值整合节点的逻辑已移除，直接使用收盘价）
        List<Double> nodes = closes.subList(minIdx, closeCount);
        int n = nodes.size();

        // 整体上涨约束：末个收盘价 > 首个收盘价
        if (requireOverallUp && nodes.get(n - 1) <= nodes.get(0))
        {
            return result;
        }

        // 偏离判定：收盘价相对前一收盘价下跌算一次偏离，同时累计偏离幅度与最高/最低点
        int dev = 0;
        double dropSum = 0;
        double maxNode = nodes.get(0);
        double minNode = nodes.get(0);
        for (int i = 1; i < n; i++)
        {
            double cur = nodes.get(i);
            double pre = nodes.get(i - 1);
            if (cur < pre)
            {
                dev++;
                dropSum += pre - cur;
            }
            if (cur > maxNode)
            {
                maxNode = cur;
            }
            if (cur < minNode)
            {
                minNode = cur;
            }
        }

        // 偏离次数不能大于 ceil(收盘价数量 * maxDeviationRatio)
        int maxDeviationCount = (int) Math.ceil(n * maxDeviationRatio);
        if (dev > maxDeviationCount)
        {
            return result;
        }

        // 偏离程度 = 偏离累计跌幅 / (最高点 - 最低点)，不能超过 maxDeviationDegree
        double range = maxNode - minNode;
        if (range > 0 && dropSum / range > maxDeviationDegree)
        {
            return result;
        }

        result.rising = true;
        result.lowDate = closeDates.get(minIdx);
        return result;
    }

    /**
     * 趋势判定结果：是否上涨 + 最低点日期
     */
    private static class TrendResult
    {
        private boolean rising;
        private String lowDate;
    }

    /**
     * 将 K 线交易日（Date）转为 yyyy-MM-dd 字符串
     */
    private String toDateString(Date date)
    {
        if (date == null)
        {
            return null;
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
    }

    /**
     * 将命中股票合并进固定分组：首次执行自动创建分组，之后每次执行把新命中的股票追加进该分组（去重）
     */
    private void mergeIntoGroup(List<Stock> matched, LocalDate endDate)
    {
        // 1. 按固定分组名查找已有分组（不含日期）
        StockGroup query = new StockGroup();
        query.setGroupName(groupName);
        StockGroup existing = null;
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups != null)
        {
            for (StockGroup g : groups)
            {
                if (groupName.equals(g.getGroupName()))
                {
                    existing = g;
                    break;
                }
            }
        }

        // 2. 分组不存在则首次创建
        if (existing == null)
        {
            StockGroup group = new StockGroup();
            group.setGroupName(groupName);
            group.setStockCodes(matched.stream().map(Stock::getStockCode).toArray(String[]::new));
            group.setCreateBy("system");
            group.setRemark(buildRemark(groupName, endDate, matched.size()));
            stockGroupService.insertGroup(group);
            log.info("首次创建分组 [{}]，命中 {} 只", groupName, matched.size());
            for (Stock s : matched)
            {
                log.info("    {} {}", s.getStockCode(), s.getStockName());
            }
            return;
        }

        // 3. 分组已存在：合并现有股票与新命中股票（按股票代码去重）
        Set<String> mergedCodes = new LinkedHashSet<>();
        List<Stock> existingStocks = stockGroupService.selectStocksByGroupId(existing.getId());
        if (existingStocks != null)
        {
            for (Stock s : existingStocks)
            {
                if (s.getStockCode() != null)
                {
                    mergedCodes.add(s.getStockCode());
                }
            }
        }

        List<String> addedCodes = new ArrayList<>();
        for (Stock s : matched)
        {
            if (s.getStockCode() != null && mergedCodes.add(s.getStockCode()))
            {
                addedCodes.add(s.getStockCode());
            }
        }

        if (addedCodes.isEmpty())
        {
            log.info("分组 [{}] 无新增股票，跳过", groupName);
            return;
        }

        // 增量插入新增股票（INSERT IGNORE 按主键去重），不覆盖已有成员，避免并发/重跑时全量覆盖丢股票
        stockGroupService.insertGroupStocksIgnore(existing.getId(), addedCodes.toArray(new String[0]));

        // 仅更新备注（不传 stockCodes，updateGroup 不会重建成员）
        StockGroup update = new StockGroup();
        update.setId(existing.getId());
        update.setUpdateBy("system");
        update.setRemark(buildRemark(groupName, endDate, mergedCodes.size()));
        stockGroupService.updateGroup(update);

        log.info("分组 [{}] 本次新增 {} 只，累计 {} 只", groupName, addedCodes.size(), mergedCodes.size());
        for (String code : addedCodes)
        {
            log.info("    {}", code);
        }
    }

    /**
     * 构造分组备注（描述筛选口径与累计命中数）
     *
     * @param title   分组标题
     * @param endDate 截止日期
     * @param total   累计命中数量
     */
    private String buildRemark(String title, LocalDate endDate, int total)
    {
        return String.format("%s（截止%s往前%d日自收盘最低点上涨，最低日到最新≥%d根K线，链上无涨停、单日涨幅≥%.1f%%，偏离次数≤ceil(n*%.0f%%)且偏离程度≤%.0f%%，累计命中%d只）",
                title, endDate, lookbackDays, minLowDays, minDailyGain, maxDeviationRatio * 100, maxDeviationDegree * 100, total);
    }

    /**
     * 解析截止日期：入参为空或解析失败时默认当天
     */
    private LocalDate parseDate(String date)
    {
        if (date == null || date.trim().isEmpty())
        {
            return LocalDate.now();
        }
        try
        {
            return LocalDate.parse(date.trim());
        }
        catch (Exception e)
        {
            log.warn("日期参数 [{}] 解析失败，使用当前日期", date);
            return LocalDate.now();
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
