package com.ruoyi.common.utils.stock;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class TradingDayUtils {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 返回 startDate ~ endDate 之间的日期列表（含端点），跳过周末（非交易日）。
     * 注意：仅过滤周末，法定节假日仍需交易日历才能精确剔除。
     */
    public static List<String> getAllDays(String startDateStr, String endDateStr) {
        LocalDate startDate = LocalDate.parse(startDateStr, DATE_FORMATTER);
        LocalDate endDate = LocalDate.parse(endDateStr, DATE_FORMATTER);
        List<String> allDays = new ArrayList<>();

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            if (!isWeekend(current)) {
                allDays.add(current.format(DATE_FORMATTER));
            }
            current = current.plusDays(1);
        }
        return allDays;
    }

    /** 判断是否为周末（非交易日） */
    public static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    public static String getDateStr(LocalDate localDate) {
       return localDate.format(DATE_FORMATTER);
    }
}