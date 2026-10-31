package com.ruoyi.quartz.task;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.StockLimitUpRequest;
import com.ruoyi.system.service.IStockLimitUpRecordService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 涨停数据采集任务（来源：财联社 CLS API）
 *
 * @author ruoyi
 */
@Component("stockLimitUpCollectTask")
public class StockLimitUpCollectTask {

    private static final Logger log = LoggerFactory.getLogger(StockLimitUpCollectTask.class);

    private static final int DEFAULT_DAYS = 2000;

    @Resource
    private IStockLimitUpRecordService stockLimitUpRecordService;

    /** 无参调用：同步最近2000天涨停数据 */
    public void syncLimitUpData() {
        syncLimitUpData(null);
    }

    /**
     * 同步涨停数据
     * @param days 往前推的天数（如 "30" 表示最近30天），不传默认2000天
     */
    public void syncLimitUpData(String days) {
        int n = parseDays(days);
        LocalDate now = LocalDate.now();
        StockLimitUpRequest request = new StockLimitUpRequest();
        request.setStartDate(TradingDayUtils.getDateStr(now.minusDays(n)));
        request.setEndDate(TradingDayUtils.getDateStr(now));
        log.info("开始同步涨停数据: {} ~ {} ({}天)", request.getStartDate(), request.getEndDate(), n);
        stockLimitUpRecordService.syncLimitUpData(request);
    }

    private int parseDays(String days) {
        if (days != null && !days.trim().isEmpty()) {
            try {
                int n = Integer.parseInt(days.trim());
                if (n > 0) return n;
            } catch (NumberFormatException e) {
                log.warn("参数解析失败: {}, 使用默认{}天", days, DEFAULT_DAYS);
            }
        }
        return DEFAULT_DAYS;
    }
}
