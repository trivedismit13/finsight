package com.finsight;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;

public class H2Functions {
    public static String dateFormat(LocalDate date, String format) {
        if (date == null) return null;
        if ("%Y-%m".equals(format)) return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        if ("%Y-%m-%d".equals(format)) return date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        return date.toString();
    }

    public static String yearWeek(LocalDate date, int mode) {
        if (date == null) return null;
        WeekFields wf = WeekFields.ISO;
        int year = date.get(wf.weekBasedYear());
        int week = date.get(wf.weekOfWeekBasedYear());
        return year + "-" + String.format("%02d", week);
    }
}
