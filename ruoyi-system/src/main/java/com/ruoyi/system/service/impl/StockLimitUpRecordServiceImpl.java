package com.ruoyi.system.service.impl;

import com.ruoyi.common.utils.stock.TradingDayUtils;
import com.ruoyi.system.domain.stock.*;
import com.ruoyi.system.mapper.StockLimitUpRecordMapper;
import com.ruoyi.system.mapper.StockMapper;
import com.ruoyi.system.service.IStockLimitUpRecordService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class StockLimitUpRecordServiceImpl implements IStockLimitUpRecordService {

    private static final Logger log = LoggerFactory.getLogger(StockLimitUpRecordServiceImpl.class);

    private static final String CLS_API_BASE = "https://x-quote.cls.cn/v2/quote/a/plate/up_down_analysis";

    @Value("${stock.cls.sign}")
    private String clsSign;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private StockLimitUpRecordMapper stockAnalysisRecordMapper;

    @Resource
    private StockMapper stockMapper;

    /** 自身代理，用于在同一事务内完成「记录入库 + 更新股票涨停日期/连板数」的多步写库（绕过自调用不生效事务代理的问题） */
    @Lazy
    @Resource
    private IStockLimitUpRecordService self;

    @Override
    public int batchSaveStockLimitUpRecords(List<StockLimitUpRecord> records) {
        return stockAnalysisRecordMapper.batchInsertStockLimitUpRecords(records);
    }

    @Override
    @Transactional
    public void saveRecordsAndUpdateStock(List<StockLimitUpRecord> records, String date) {
        batchSaveStockLimitUpRecords(records);
        updateStockLastLimitUpDate(records, date);
        updateStockMaxLimitUpBoard(records, date);
    }

    @Override
    public String syncLimitUpData(StockLimitUpRequest request) {
        String startDate = request.getStartDate();
        String endDate = request.getEndDate();

        // 增量采集：从已入库的最大交易日期之后开始，避免每次全量重跑（大跨度也仅补增量）
        String maxTradeDate = stockAnalysisRecordMapper.selectMaxTradeDate();
        if (maxTradeDate != null && !maxTradeDate.isEmpty() && maxTradeDate.compareTo(startDate) >= 0) {
            LocalDate next = LocalDate.parse(maxTradeDate, DateTimeFormatter.ofPattern("yyyyMMdd")).plusDays(1);
            String nextStr = next.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            if (nextStr.compareTo(endDate) <= 0) {
                log.info("检测到已采集至 {}，本次增量采集 {} ~ {}", maxTradeDate, nextStr, endDate);
                startDate = nextStr;
            } else {
                log.info("已采集至 {}，无新增交易日需采集", maxTradeDate);
                return "已是最新，无需采集";
            }
        }

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
        String url = String.format("%s?up_limit=1&date=%s&sign=%s", CLS_API_BASE, date, clsSign);

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

        List<StockLimitUpRecord> records = new ArrayList<>();
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

                StockLimitUpRecord record = new StockLimitUpRecord();
                record.setTradeDate(parseTradeDate(date));
                record.setSecuCode(normalizeSecuCode(stockInfo.getSecu_code()));
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
            self.saveRecordsAndUpdateStock(records, date);
        }
        return records.size();
    }

    /** 采集完成后，将当日涨停股票的最新涨停日期更新为该交易日（仅向前不回退） */
    private void updateStockLastLimitUpDate(List<StockLimitUpRecord> records, String date) {
        List<String> codes = records.stream()
                .map(StockLimitUpRecord::getSecuCode)
                .filter(c -> c != null && !c.isEmpty())
                .distinct()
                .collect(Collectors.toList());
        if (codes.isEmpty()) {
            return;
        }
        int updated = stockMapper.updateLastLimitUpDate(codes, parseTradeDate(date));
        log.info("日期 {} 更新最新涨停日期 {} 只", date, updated);
    }

    /** 采集完成后，解析 up_num（如「8天8板」）取板数，更新各股票历史最高连板数及对应交易日（仅增不减） */
    private void updateStockMaxLimitUpBoard(List<StockLimitUpRecord> records, String date) {
        Map<String, Integer> codeMaxBoard = new HashMap<>();
        for (StockLimitUpRecord record : records) {
            Integer board = parseBoardCount(record.getUpNum());
            String code = record.getSecuCode();
            if (board == null || code == null || code.isEmpty()) {
                continue;
            }
            Integer existing = codeMaxBoard.get(code);
            if (existing == null || board > existing) {
                codeMaxBoard.put(code, board);
            }
        }
        Date boardDate = parseTradeDate(date);
        int updated = 0;
        for (Map.Entry<String, Integer> entry : codeMaxBoard.entrySet()) {
            updated += stockMapper.updateMaxLimitUpBoard(entry.getKey(), entry.getValue(), boardDate);
        }
        log.info("更新历史最高连板数 {} 只", updated);
    }

    /** 解析连板描述（如「8天8板」「3天2板」）中的板数；为空或无法解析返回 null */
    private Integer parseBoardCount(String upNum) {
        if (upNum == null || upNum.trim().isEmpty()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(\\d+)板").matcher(upNum);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 解析 yyyyMMdd 字符串为 java.sql.Date（stock_limit_up_record.trade_date 为 date 类型） */
    private Date parseTradeDate(String yyyyMMdd) {
        return Date.valueOf(LocalDate.parse(yyyyMMdd, DateTimeFormatter.ofPattern("yyyyMMdd")));
    }

    /** 归一化财联社证券代码：去 sh/sz 前缀、.BJ 后缀，落库前统一为纯代码以匹配 stock.stock_code */
    private String normalizeSecuCode(String secuCode) {
        if (secuCode == null || secuCode.isEmpty()) {
            return secuCode;
        }
        String lower = secuCode.toLowerCase();
        if (secuCode.length() > 2 && (lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj"))) {
            return secuCode.substring(2);
        }
        if (secuCode.length() > 3 && secuCode.toUpperCase().endsWith(".BJ")) {
            return secuCode.substring(0, secuCode.length() - 3);
        }
        return secuCode;
    }
}