package com.ruoyi.common.utils.stock;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TradingDayUtils {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static List<String> getAllDays(String startDateStr, String endDateStr) {
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        List<String> allDays = new ArrayList<>();

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            allDays.add(current.format(DATE_FORMATTER));
            current = current.plusDays(1);
        }
        return allDays;
    }

    public static String getDateStr(LocalDate localDate) {
       return localDate.format(DATE_FORMATTER);
    }
}