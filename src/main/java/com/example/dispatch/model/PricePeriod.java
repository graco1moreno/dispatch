package com.example.dispatch.model;

import lombok.Getter;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 电价时段管理类
 */
public class PricePeriod {
    // 电价类型枚举
    @Getter
    public enum PriceType {
        VALLEY(0, "谷时段", 0.3),    // 0-8时
        NORMAL(1, "平时段", 0.6),    // 8-10时, 12-14时, 19-24时
        PEAK(2, "峰时段", 0.8),      // 10-12时
        SHARP(3, "尖时段", 1.0);     // 14-19时

        private final int code;
        private final String name;
        private final double price;

        PriceType(int code, String name, double price) {
            this.code = code;
            this.name = name;
            this.price = price;
        }
    }

    /**
     * 获取指定时间的电价类型
     * @param dateTime 时间
     * @return 电价类型
     */
    public static PriceType getPriceType(LocalDateTime dateTime) {
        int hour = dateTime.getHour();
        
        if (hour >= 0 && hour < 8) {
            return PriceType.VALLEY;
        } else if ((hour >= 8 && hour < 10) || 
                   (hour >= 12 && hour < 14) || 
                   (hour >= 19 && hour < 24)) {
            return PriceType.NORMAL;
        } else if (hour >= 10 && hour < 12) {
            return PriceType.PEAK;
        } else { // hour >= 14 && hour < 19
            return PriceType.SHARP;
        }
    }

    /**
     * 获取下一个较低电价时段的开始时间
     * @param currentTime 当前时间
     * @return 下一个较低电价时段的开始时间，如果当前已经是最低电价时段则返回null
     */
    public static LocalDateTime getNextLowerPricePeriodStart(LocalDateTime currentTime) {
        int currentHour = currentTime.getHour();
        PriceType currentType = getPriceType(currentTime);
        
        switch (currentType) {
            case SHARP: // 从尖时段到平时段
                if (currentHour >= 14 && currentHour < 19) {
                    return currentTime.withHour(19).withMinute(0).withSecond(0).withNano(0); // 19:00
                }
                break;
            case PEAK: // 从峰时段到平时段
                if (currentHour >= 10 && currentHour < 12) {
                    return currentTime.withHour(12).withMinute(0).withSecond(0).withNano(0); // 12:00
                }
                break;
            case NORMAL: // 从平时段到谷时段
                if (currentHour >= 19) {
                    return currentTime.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0); // 00:00
                }
                break;
            case VALLEY: // 已经是最低电价时段
                return null;
        }
        return null;
    }

    /**
     * 判断是否应该提前换电
     * @param currentTime 当前时间
     * @param nextTripEndTime 下一次运输结束时间
     * @return 是否应该提前换电
     */
    public static boolean shouldExchangeEarly(LocalDateTime currentTime, LocalDateTime nextTripEndTime) {
        PriceType currentType = getPriceType(currentTime);
        PriceType nextTripEndType = getPriceType(nextTripEndTime);
        
        // 如果当前是较低电价时段（谷时段或平时段），而下一次运输结束时是较高电价时段（峰时段或尖时段）
        return (currentType == PriceType.VALLEY || currentType == PriceType.NORMAL) &&
               (nextTripEndType == PriceType.PEAK || nextTripEndType == PriceType.SHARP);
    }

    /**
     * 判断是否应该延后换电
     * @param currentTime 当前时间
     * @param nextTripEndTime 下一次运输结束时间
     * @return 是否应该延后换电
     */
    public static boolean shouldDelayExchange(LocalDateTime currentTime, LocalDateTime nextTripEndTime) {
        PriceType currentType = getPriceType(currentTime);
        PriceType nextTripEndType = getPriceType(nextTripEndTime);
        
        // 如果当前是较高电价时段（峰时段或尖时段），而下一次运输结束时是较低电价时段（谷时段或平时段）
        return (currentType == PriceType.PEAK || currentType == PriceType.SHARP) &&
               (nextTripEndType == PriceType.VALLEY || nextTripEndType == PriceType.NORMAL);
    }
} 