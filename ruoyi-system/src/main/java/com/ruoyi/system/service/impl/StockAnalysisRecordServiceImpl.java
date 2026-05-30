package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.*;
import com.ruoyi.system.mapper.StockAnalysisRecordMapper;
import com.ruoyi.system.service.IStockAnalysisRecordService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockAnalysisRecordServiceImpl implements IStockAnalysisRecordService {

    private static final Logger log = LoggerFactory.getLogger(StockAnalysisRecordServiceImpl.class);

    private static final String CLS_API_BASE = "https://x-quote.cls.cn/v2/quote/a/plate/up_down_analysis";

    @Value("${cls.sign}")
    private String clsSign;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private StockAnalysisRecordMapper stockAnalysisRecordMapper;

    @Override
    public int batchSaveStockAnalysisRecords(List<StockAnalysisRecord> records) {
        return stockAnalysisRecordMapper.batchInsertStockAnalysisRecords(records);
    }

    @Override
    public String downloadAnalysisData(StockAnalysisRequest request) {
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();

        List<String> allDays = TradingDayUtils.getAllDays(startDate, endDate);
        if (allDays.isEmpty()) {
            return "日期范围内无交易日数据";
        }

        int totalSaved = 0;
        List<String> failedDates = new ArrayList<>();

        for (String date : allDays) {
            try {
                int saved = processDate(date);
                totalSaved += saved;
                log.info("日期 {} 处理完成，保存 {} 条记录", date, saved);
            } catch (Exception e) {
                log.error("日期 {} 处理失败", date, e);
                failedDates.add(date);
            }
        }

        String msg = String.format("处理完成：共 %d 天，成功保存 %d 条记录", allDays.size(), totalSaved);
        if (!failedDates.isEmpty()) {
            msg += String.format("，%d 天失败: %s", failedDates.size(), String.join(",", failedDates));
        }
        log.info(msg);
        return msg;
    }

    /**
     * 处理单个日期的涨停分析数据
     *
     * @param date 交易日期 yyyyMMdd
     * @return 保存的记录数
     */
    private int processDate(String date) {
        String url = String.format("%s?up_limit=0&date=%s&sign=%s", CLS_API_BASE, date, clsSign);

        StockResult response;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Origin", "https://api3.cls.cn");
            headers.set("Referer", "https://api3.cls.cn/");
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<StockResult> responseEntity = restTemplate.exchange(
                url, HttpMethod.GET, entity, StockResult.class);
            response = responseEntity.getBody();
        } catch (RestClientException e) {
            log.error("调用财联社API失败，日期: {}", date, e);
            return 0;
        }

        if (response == null || response.getCode() != 200) {
            log.warn("财联社API返回异常，日期: {}, 状态: {}", date,
                response != null ? response.getCode() : "null");
            return 0;
        }

        StockData data = response.getData();
        if (data == null) {
            log.warn("财联社API返回数据为空，日期: {}", date);
            return 0;
        }

        List<PlateStock> plateStocks = data.getPlate_stock();
        if (plateStocks == null || plateStocks.isEmpty()) {
            log.warn("当日无板块涨停数据，日期: {}", date);
            return 0;
        }

        List<StockAnalysisRecord> records = new ArrayList<>();
        for (PlateStock plateStock : plateStocks) {
            List<PlateStockInfo> stockList = plateStock.getStock_list();
            if (stockList == null) {
                continue;
            }

            for (PlateStockInfo stockInfo : stockList) {
                String time = stockInfo.getTime();
                String tradeDateFromTime = time != null && time.length() >= 10
                    ? time.substring(0, 10).replace("-", "") : "";

                if (!tradeDateFromTime.equals(date)) {
                    continue;
                }

                StockAnalysisRecord record = new StockAnalysisRecord();
                record.setTradeDate(date);
                record.setSecuCode(stockInfo.getSecu_code());
                record.setSecuName(stockInfo.getSecu_name());
                record.setChangeRate(stockInfo.getChange());
                record.setLastPx(stockInfo.getLast_px());
                record.setCmc(stockInfo.getCmc());
                record.setTime(stockInfo.getTime());
                record.setUpNum(stockInfo.getUp_num());
                record.setUpReason(stockInfo.getUp_reason());

                record.setPlateSecuCode(plateStock.getSecu_code());
                record.setPlateSecuName(plateStock.getSecu_name());
                record.setPlateChangeRate(plateStock.getChange());
                record.setPlateUpReason(plateStock.getUp_reason());
                record.setPlateStockUpNum(plateStock.getPlate_stock_up_num());

                record.setUpTags(stockInfo.getUp_tags() != null
                    ? String.join(",", stockInfo.getUp_tags()) : "");

                record.setCreateBy("system");

                records.add(record);
            }
        }

        if (!records.isEmpty()) {
            batchSaveStockAnalysisRecords(records);
        }
        return records.size();
    }
}