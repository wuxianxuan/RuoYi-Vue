package com.ruoyi.quartz.task;

import com.ruoyi.common.enums.StockMarketType;
import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.Stock;
import com.ruoyi.system.domain.stock.StockAnalysisRecord;
import com.ruoyi.system.domain.stock.StockAnalysisRequest;
import com.ruoyi.system.mapper.StockAnalysisRecordMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockAnalysisRecordService;
import com.ruoyi.system.service.impl.StockAnalysisRecordServiceImpl;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component("stockDataCollectTask")
public class StockDataCollectTask {

    private static final Logger log = LoggerFactory.getLogger(StockDataCollectTask.class);

    @Resource
    private IStockAnalysisRecordService stockAnalysisRecordService;
    @Resource
    private StockAnalysisRecordMapper stockAnalysisRecordMapper;
    @Resource
    private StockMapper stockMapper;

    public void downloadAnalysisData(){
        LocalDate now = LocalDate.now();
        StockAnalysisRequest request = new StockAnalysisRequest();
        request.setStartDate(TradingDayUtils.getDateStr(now));
        request.setEndDate(TradingDayUtils.getDateStr(now.minusDays(30)));
        stockAnalysisRecordService.downloadAnalysisData(request);
    }

    @Transactional(rollbackFor = Exception.class)
    public void collectStockData(){
        LocalDate date = LocalDate.now().minusDays(31);
        List<StockAnalysisRecord> records = stockAnalysisRecordMapper.collectStockData(TradingDayUtils.getDateStr(date));
        if (records == null || records.isEmpty()){
            return;
        }
        List<Stock> insertList = new ArrayList<>();
        for (StockAnalysisRecord item : records) {
            Stock stock = parseStock(item);
            if (stock == null) {
                continue;
            }
            insertList.add(stock);
        }
        if (!insertList.isEmpty()) {
            stockMapper.batchInsertListIgnoreSame(insertList);
        }
    }

    /**
     * 从 StockAnalysisRecord 解析 Stock 对象
     *
     * @param record 分析记录
     * @return 解析后的 Stock，解析失败返回 null
     */
    private Stock parseStock(StockAnalysisRecord record) {
        String secuCode = record.getSecuCode();
        if (secuCode == null || secuCode.isEmpty()) {
            log.warn("股票编码为空，跳过记录: 股票名称={}", record.getSecuName());
            return null;
        }

        String shInfo = StockMarketType.SH.getInfo();
        String szInfo = StockMarketType.SZ.getInfo();
        String bjCode = StockMarketType.BJ.getCode();

        Stock stock = new Stock();

        if (secuCode.startsWith(shInfo)) {
            stock.setMarket(StockMarketType.SH.getCode());
            stock.setStockCode(secuCode.substring(shInfo.length()));
        } else if (secuCode.startsWith(szInfo)) {
            stock.setMarket(StockMarketType.SZ.getCode());
            stock.setStockCode(secuCode.substring(szInfo.length()));
        } else if (secuCode.endsWith(bjCode)) {
            stock.setMarket(StockMarketType.BJ.getCode());
            stock.setStockCode(secuCode.substring(0, 6));
        } else {
            log.warn("无法识别市场类型，跳过记录: secuName={}, secuCode={}",
                    record.getSecuName(), record.getSecuCode());
            return null;
        }

        // 使用解析后的股票代码作为名称，后续可通过其他数据源更新为真实名称
        stock.setStockName(record.getSecuName());
        return stock;
    }
}
