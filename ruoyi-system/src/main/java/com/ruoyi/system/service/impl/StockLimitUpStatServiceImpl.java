package com.ruoyi.system.service.impl;

import com.ruoyi.system.domain.stock.StockLimitUpStat;
import com.ruoyi.system.mapper.StockLimitUpStatMapper;
import com.ruoyi.system.service.IStockLimitUpStatService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票近30天（含今天）涨停次数统计实现
 */
@Service
public class StockLimitUpStatServiceImpl implements IStockLimitUpStatService {

    private static final Logger log = LoggerFactory.getLogger(StockLimitUpStatServiceImpl.class);

    @Resource
    private StockLimitUpStatMapper stockLimitUpStatMapper;

    @Override
    @Transactional
    public void rebuildStats(String startDate, String endDate) {
        // secu_code 已在采集同步阶段归一化为纯代码，此处直接聚合
        List<StockLimitUpStat> stats = stockLimitUpStatMapper.selectAggregatedByRange(startDate, endDate);
        int deleted = stockLimitUpStatMapper.deleteAll();
        int inserted = (stats == null || stats.isEmpty()) ? 0 : stockLimitUpStatMapper.batchInsert(stats);
        log.info("涨停次数统计完成: 删除旧记录 {} 条, 写入 {} 条 (统计区间 {} ~ {})", deleted, inserted, startDate, endDate);
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
