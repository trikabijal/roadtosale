package com.checkSheet.helper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Calendar;
import java.util.Date;

public class DateHelper {
    static SimpleDateFormat dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    static LocalDate todaydate = LocalDate.now();

    public static String getCurrentDateToString() {
        Calendar calendar = Calendar.getInstance();
        return dateTimeFormat.format(calendar.getTime());
    }

    public static Date getDateFromString(String s) throws ParseException {
        return dateTimeFormat.parse(s);
    }

    public static String getDateTimeToString(Date date) {
        if(date == null) return null;
        else return dateTimeFormat.format(date);
    }

    public static String getDateToString(Date date) {
        if(date == null) return null;
        else return dateFormat.format(date);
    }
    public static Date getDateFromStringNoTime(String s) throws ParseException {
        return dateFormat.parse(s);
    }

    public static String getDateToString(Date date, String formate) {
        if(date == null) return null;
        SimpleDateFormat dateFormat = new SimpleDateFormat(formate);
        return dateFormat.format(Calendar.getInstance().getTime());
    }

    public static String getDateToStringFormat(Date date, String formate) {
        if(date == null) return null;
        SimpleDateFormat dateFormat = new SimpleDateFormat(formate);
        return dateFormat.format(date);
    }

    public static LocalDate getTodaysDate() {
        return todaydate;
    }

    public static Date getStringToDateFormat(String date, String formate) throws ParseException {
        if(date == null) return null;
        SimpleDateFormat dateFormat = new SimpleDateFormat(formate);
        return dateFormat.parse(date);
    }

    public static Date getDateOfCurrentWeek(int day){
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.DAY_OF_WEEK,  calendar.getFirstDayOfWeek() + day);
        return calendar.getTime();
    }

    public static Date getDateOfCurrentMonth(int day){
        Calendar calendar = Calendar.getInstance();
        // Set to the first day of the month
        calendar.set(Calendar.DAY_OF_MONTH, day);
        return calendar.getTime();
    }

    public static Date getLastDateOfCurrentMonth(){
        Calendar calendar = Calendar.getInstance();
        // Set to the first day of the month
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH));
        return calendar.getTime();
    }
}
