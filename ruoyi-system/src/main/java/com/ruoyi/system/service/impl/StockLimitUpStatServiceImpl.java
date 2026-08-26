package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockLimitUpStat;
import com.ruoyi.system.mapper.StockLimitUpStatMapper;
import com.ruoyi.system.service.IStockLimitUpStatService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票近1年（不含最近7天）涨停次数统计实现
 */
@Service
public class StockLimitUpStatServiceImpl implements IStockLimitUpStatService {

    private static final Logger log = LoggerFactory.getLogger(StockLimitUpStatServiceImpl.class);

    @Resource
    private StockLimitUpStatMapper stockLimitUpStatMapper;

    @Override
    @Transactional
    public void rebuildStats(String startDate, String endDate) {
        List<StockLimitUpStat> raw = stockLimitUpStatMapper.selectAggregatedByRange(startDate, endDate);
        List<StockLimitUpStat> stats = new ArrayList<>();
        if (raw != null) {
            for (StockLimitUpStat r : raw) {
                // 财联社 secu_code 带 sh/sz 前缀或 .BJ 后缀，定时任务阶段归一化为纯代码以匹配 stock.stock_code
                r.setStockCode(normalizeSecuCode(r.getStockCode()));
                stats.add(r);
            }
        }
        int deleted = stockLimitUpStatMapper.deleteAll();
        int inserted = stats.isEmpty() ? 0 : stockLimitUpStatMapper.batchInsert(stats);
        log.info("涨停次数统计完成: 删除旧记录 {} 条, 写入 {} 条 (统计区间 {} ~ {})", deleted, inserted, startDate, endDate);
    }

    /** 归一化财联社证券代码：去 sh/sz 前缀、.BJ 后缀 */
    private String normalizeSecuCode(String secuCode) {
        if (secuCode == null || secuCode.isEmpty()) {
            return secuCode;
        }
        String lower = secuCode.toLowerCase();
        if (secuCode.length() > 2 && (lower.startsWith("sh") || lower.startsWith("sz"))) {
            return secuCode.substring(2);
        }
        if (secuCode.length() > 3 && secuCode.toUpperCase().endsWith(".BJ")) {
            return secuCode.substring(0, secuCode.length() - 3);
        }
        return secuCode;
    }

    @Override
    public Map<String, Integer> getLimitUpCountMap(List<String> codes) {
        Map<String, Integer> map = new HashMap<>();
        if (codes == null || codes.isEmpty()) {
            return map;
        }
        List<StockLimitUpStat> stats = stockLimitUpStatMapper.selectByCodes(codes);
        if (stats != null) {
            for (StockLimitUpStat s : stats) {
                map.put(s.getStockCode(), s.getLimitUpCount() == null ? 0 : s.getLimitUpCount());
            }
        }
        return map;
    }
}
