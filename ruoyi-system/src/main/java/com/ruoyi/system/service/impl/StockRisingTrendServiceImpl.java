package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockGroup;
import com.ruoyi.system.domain.stock.StockKline;
import com.ruoyi.system.domain.stock.StockRisingState;
import com.ruoyi.system.mapper.StockKlineMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.mapper.StockRisingStateMapper;
import com.ruoyi.system.service.IStockGroupService;
import com.ruoyi.system.service.IStockRisingTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 趋势上涨选股实现（波段状态机算法）
 *
 * 对每只股票按日K线走一遍波段状态机（进池 → 上升/震荡/回调 → 删除）：
 *   1. 进池条件（未入池时，看最近 entryWindow 根日K）：
 *      - 窗口内无涨停（单日涨幅 >= limitUpThreshold%）；
 *      - 区间涨幅 (最高-最低)/最低 >= minGain%；
 *      - 当前收盘创 entryWindow 日新高；
 *      - 当前收盘 > MA(maPeriod)（中期趋势约束，拦截跌深后的超跌反弹）。
 *   2. 入池后每日状态迁移（回撤用 ATR 归一化，避免小涨幅时阈值失真）：
 *      - 创新高：状态=上升，抬高 highPrice/highDate；若此前是「回调」则把回调谷记为新 lowPrice/lowDate；
 *      - 未创新高：回撤 = (highPrice - 当前收盘) / ATR(atrPeriod)；
 *          > deleteAtr  → 删除（移出池）；
 *          <= shakeAtr → 震荡；
 *          其余        → 回调。
 *
 * 每日全量重算：从截止日往前回看 lookbackDays 个日历日，从头跑状态机取当日终态，
 * 幂等、无状态污染，漏跑/重跑不影响结果。命中结果全量覆盖「趋势上涨选股」分组，
 * 并把每只池内股票的波段状态写入 stock_rising_state 表。
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
    private StockRisingStateMapper stockRisingStateMapper;

    /** 回看日历天数（重算锚点，需覆盖 MA/ATR 周期 + 波段历史） */
    @Value("${stock.rising-trend.lookback-days:90}")
    private int lookbackDays;

    /** 进池窗口天数（N）：近 N 日内无涨停、区间涨幅达标、创 N 日新高 */
    @Value("${stock.rising-trend.entry-window:5}")
    private int entryWindow;

    /** 进池区间涨幅阈值(%)：窗口内 (最高-最低)/最低 >= 该值 */
    @Value("${stock.rising-trend.min-gain:5.0}")
    private double minGain;

    /** 涨停判定阈值(%)：窗口内单日涨幅 >= 该值视为涨停，进池时过滤 */
    @Value("${stock.rising-trend.limit-up-threshold:9.8}")
    private double limitUpThreshold;

    /** 趋势过滤均线周期：进池要求收盘 > MA(该周期) */
    @Value("${stock.rising-trend.ma-period:20}")
    private int maPeriod;

    /** ATR 周期 */
    @Value("${stock.rising-trend.atr-period:20}")
    private int atrPeriod;

    /** 震荡阈值(ATR倍数)：回撤 <= 该值视为震荡 */
    @Value("${stock.rising-trend.shake-atr:1.0}")
    private double shakeAtr;

    /** 删除阈值(ATR倍数)：回撤 > 该值移出积累池 */
    @Value("${stock.rising-trend.delete-atr:2.0}")
    private double deleteAtr;

    /** 是否排除 ST/*ST 股票 */
    @Value("${stock.rising-trend.exclude-st:true}")
    private boolean excludeSt;

    /** 扫描市场列表（逗号分隔） */
    @Value("${stock.rising-trend.markets:SH,SZ}")
    private String markets;

    /** 目标分组名称（固定名，每日全量覆盖，反映当前池内股票） */
    @Value("${stock.rising-trend.group-name:趋势上涨选股}")
    private String groupName;

    /** 排除的板块代码前缀（逗号分隔），默认排除创业板(300/301)、科创板(688/689) */
    @Value("${stock.rising-trend.exclude-code-prefixes:300,301,688,689}")
    private String excludeCodePrefixes;

    @Override
    public void execute(String date)
    {
        LocalDate endDate = parseDate(date);
        LocalDate startDate = endDate.minusDays(lookbackDays);
        log.info("========== 趋势上涨选股开始 ==========");
        log.info("参数：endDate={} startDate={} lookbackDays={} entryWindow={} minGain={} maPeriod={} atrPeriod={} shakeAtr={} deleteAtr={} excludeSt={} markets={} excludeCodePrefixes={}",
                endDate, startDate, lookbackDays, entryWindow, minGain, maPeriod, atrPeriod, shakeAtr, deleteAtr, excludeSt, markets, excludeCodePrefixes);

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
        Map<String, List<StockKline>> klineMap = (allKlines == null)
                ? new LinkedHashMap<>()
                : allKlines.stream().collect(Collectors.groupingBy(StockKline::getStockCode));

        // 3. 逐只跑波段状态机，收集终态在池的股票
        List<StockRisingState> pool = new ArrayList<>();
        int up = 0, shake = 0, cb = 0;
        for (Stock s : stockMap.values())
        {
            SwingState st = runStateMachine(klineMap.get(s.getStockCode()));
            if (st == null || !st.inPool)
            {
                continue;
            }
            StockRisingState row = new StockRisingState();
            row.setStockCode(s.getStockCode());
            row.setStatus(st.status);
            row.setHighPrice(st.highPrice);
            row.setHighDate(st.highDate);
            row.setLowPrice(st.lowPrice);
            row.setLowDate(st.lowDate);
            row.setEnterDate(st.enterDate);
            pool.add(row);
            if ("上升".equals(st.status)) up++;
            else if ("震荡".equals(st.status)) shake++;
            else cb++;
        }
        log.info("最终池 {} 只（上升 {}，震荡 {}，回调 {}）", pool.size(), up, shake, cb);

        // 4. 全量覆盖分组 + 波段状态表
        overwriteGroup(pool, endDate, up, shake, cb);
        stockRisingStateMapper.deleteAll();
        if (!pool.isEmpty())
        {
            stockRisingStateMapper.insertBatch(pool);
        }
        log.info("波段状态表已覆盖 {} 条", pool.size());
        log.info("========== 趋势上涨选股完成 ==========");
    }

    /**
     * 判定一组日K线是否仍处于上升趋势（末根K线时是否在池内）
     */
    @Override
    public boolean isRisingTrend(List<StockKline> klines)
    {
        SwingState st = runStateMachine(klines);
        return st != null && st.inPool;
    }

    /**
     * 对一组日K线跑波段状态机，返回终态
     */
    private SwingState runStateMachine(List<StockKline> klines)
    {
        SwingState st = new SwingState();
        if (klines == null || klines.isEmpty())
        {
            return st;
        }
        List<StockKline> list = klines.stream()
                .filter(k -> k.getTradeTime() != null && k.getClosePrice() != null)
                .sorted(Comparator.comparing(StockKline::getTradeTime))
                .collect(Collectors.toList());
        int n = list.size();
        if (n < entryWindow)
        {
            return st;
        }

        double[] o = new double[n];
        double[] h = new double[n];
        double[] l = new double[n];
        double[] c = new double[n];
        String[] d = new String[n];
        for (int i = 0; i < n; i++)
        {
            StockKline k = list.get(i);
            d[i] = toDateString(k.getTradeTime());
            o[i] = doubleOf(k.getOpenPrice());
            h[i] = doubleOf(k.getHighPrice());
            l[i] = doubleOf(k.getLowPrice());
            c[i] = doubleOf(k.getClosePrice());
        }

        for (int di = 0; di < n; di++)
        {
            if (di < entryWindow - 1)
            {
                continue;
            }
            double cur = c[di];
            int loIdx = di - entryWindow + 1;
            boolean hasLimitUp = false;
            for (int i = loIdx; i <= di; i++)
            {
                if (i > 0 && c[i - 1] > 0 && (c[i] - c[i - 1]) / c[i - 1] * 100 >= limitUpThreshold)
                {
                    hasLimitUp = true;
                    break;
                }
            }

            if (!st.inPool)
            {
                if (hasLimitUp)
                {
                    continue;
                }
                double low = Double.MAX_VALUE, high = -Double.MAX_VALUE;
                for (int i = loIdx; i <= di; i++)
                {
                    if (c[i] < low) low = c[i];
                    if (c[i] > high) high = c[i];
                }
                double gain = low > 0 ? (high - low) / low * 100 : 0;
                boolean newHigh = cur >= high;
                boolean aboveMA = cur > ma(c, di, maPeriod);
                if (gain >= minGain && newHigh && aboveMA)
                {
                    st.inPool = true;
                    st.status = "上升";
                    st.enterDate = d[di];
                    st.lowPrice = low;
                    st.lowDate = d[loIdx + argmin(c, loIdx, di)];
                    st.highPrice = high;
                    st.highDate = d[di];
                    st.highIdx = di;
                }
            }
            else
            {
                if (cur >= st.highPrice)
                {
                    // 创新高
                    if ("回调".equals(st.status))
                    {
                        // 把回调谷记为新的阶段低点（低点抬高）
                        double nl = Double.MAX_VALUE;
                        int nlIdx = -1;
                        for (int i = st.highIdx + 1; i <= di; i++)
                        {
                            if (c[i] < nl) { nl = c[i]; nlIdx = i; }
                        }
                        if (nlIdx >= 0)
                        {
                            st.lowPrice = nl;
                            st.lowDate = d[nlIdx];
                        }
                    }
                    st.highPrice = cur;
                    st.highDate = d[di];
                    st.highIdx = di;
                    st.status = "上升";
                }
                else
                {
                    // 未创新高，按 ATR 归一化回撤判定
                    double atrVal = atr(h, l, c, di);
                    double pullback = atrVal > 0 ? (st.highPrice - cur) / atrVal : 0;
                    if (pullback > deleteAtr)
                    {
                        st.inPool = false;
                    }
                    else if (pullback <= shakeAtr)
                    {
                        st.status = "震荡";
                    }
                    else
                    {
                        st.status = "回调";
                    }
                }
            }
        }
        return st;
    }

    /**
     * ATR（平均真实波幅），周期 atrPeriod
     */
    private double atr(double[] h, double[] l, double[] c, int di)
    {
        int from = Math.max(1, di - atrPeriod + 1);
        double sum = 0;
        int cnt = 0;
        for (int i = from; i <= di; i++)
        {
            double tr = Math.max(h[i] - l[i],
                    Math.max(Math.abs(h[i] - c[i - 1]), Math.abs(l[i] - c[i - 1])));
            sum += tr;
            cnt++;
        }
        return cnt > 0 ? sum / cnt : 0;
    }

    /**
     * 收盘价 MA（简单移动平均），周期 p
     */
    private double ma(double[] c, int di, int p)
    {
        int from = Math.max(0, di - p + 1);
        double sum = 0;
        for (int i = from; i <= di; i++)
        {
            sum += c[i];
        }
        return sum / (di - from + 1);
    }

    /**
     * 返回 [from, to] 区间内最小值相对 from 的偏移
     */
    private int argmin(double[] c, int from, int to)
    {
        int m = from;
        for (int i = from; i <= to; i++)
        {
            if (c[i] < c[m]) m = i;
        }
        return m - from;
    }

    /**
     * 波段状态
     */
    private static class SwingState
    {
        private boolean inPool;
        private String status;
        private double highPrice;
        private String highDate;
        private int highIdx;
        private double lowPrice;
        private String lowDate;
        private String enterDate;
    }

    /**
     * 全量覆盖「趋势上涨选股」分组（首次创建，之后重建成员）
     */
    private void overwriteGroup(List<StockRisingState> pool, LocalDate endDate, int up, int shake, int cb)
    {
        String[] codes = pool.stream().map(StockRisingState::getStockCode).toArray(String[]::new);
        StockGroup existing = findGroupByName(groupName);
        if (existing == null)
        {
            StockGroup group = new StockGroup();
            group.setGroupName(groupName);
            group.setStockCodes(codes);
            group.setCreateBy("system");
            group.setRemark(buildRemark(endDate, pool.size(), up, shake, cb));
            stockGroupService.insertGroup(group);
            log.info("首次创建分组 [{}]，{} 只", groupName, pool.size());
            return;
        }
        StockGroup update = new StockGroup();
        update.setId(existing.getId());
        update.setStockCodes(codes);
        update.setUpdateBy("system");
        update.setRemark(buildRemark(endDate, pool.size(), up, shake, cb));
        stockGroupService.updateGroup(update);
        log.info("覆盖分组 [{}]，{} 只", groupName, pool.size());
    }

    /**
     * 构造分组备注
     */
    private String buildRemark(LocalDate endDate, int total, int up, int shake, int cb)
    {
        return String.format("%s（截止%s，进池=近%d日涨≥%.0f%%且创%d日新高且收盘>MA%d，波段=震荡≤%.0fATR/删除>%.0fATR，当前%d只：上升%d/震荡%d/回调%d）",
                groupName, endDate, entryWindow, minGain, entryWindow, maPeriod, shakeAtr, deleteAtr, total, up, shake, cb);
    }

    /**
     * 按精确名称查找分组，找不到返回 null
     */
    private StockGroup findGroupByName(String name)
    {
        StockGroup query = new StockGroup();
        query.setGroupName(name);
        List<StockGroup> groups = stockGroupService.selectGroupList(query);
        if (groups != null)
        {
            for (StockGroup g : groups)
            {
                if (name.equals(g.getGroupName()))
                {
                    return g;
                }
            }
        }
        return null;
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
     * 判断股票代码是否命中任一排除前缀
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

    /**
     * BigDecimal 转 double（空值按 0）
     */
    private double doubleOf(BigDecimal v)
    {
        return v == null ? 0d : v.doubleValue();
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
}
