package com.example.dispatch.model;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 换电记录类
 */
@Data
public class ExchangeRecord {
    private String truckNo;                  // 车牌号
    private BigDecimal soc;                  // 换电时剩余电量百分比
    private String capacity;                 // 电池额定容量(kWh)
    private LocalDateTime startAwaitTime;    // 开始等待换电时间
    private LocalDateTime startExchangeTime; // 开始换电时间
    private LocalDateTime useBatteryTime;    // 使用电池的充满时间
    private int duration;                    // 电池充满时长
    private LocalDateTime batteryChargeCompleteTime;   // 电池充满时间
    private String positionNo;               // 电池位置编号
    private int transporFrequency;           // 运输次数
    private String startAwaitTimeStr;              // 开始等待换电时间
    private String startExchangeTimeStr;           // 开始换电时间
    private String useBatteryTimeStr;              // 使用电池的充满时间
    private String batteryChargeCompleteTimeStr;   // 电池充满时间


    public ExchangeRecord() {
    }

    public ExchangeRecord(String truckNo, BigDecimal soc, BigDecimal capacity, LocalDateTime startAwaitTime,
                          LocalDateTime startExchangeTime, LocalDateTime useBatteryTime, int duration,
                          LocalDateTime batteryChargeCompleteTime, String positionNo, int transporFrequency) {
        this.truckNo = truckNo;
        this.soc = soc.setScale(2, RoundingMode.HALF_UP);
        this.capacity = String.valueOf(capacity.intValue());
        this.startAwaitTime = startAwaitTime;
        this.startExchangeTime = startExchangeTime;
        this.useBatteryTime = useBatteryTime;
        this.duration = duration;
        this.batteryChargeCompleteTime = batteryChargeCompleteTime;
        this.positionNo = positionNo;
        this.transporFrequency = transporFrequency;
    }

    // 兼容旧版构造函数
    public ExchangeRecord(String truckNo, double soc, double capacity, LocalDateTime startAwaitTime,
                          LocalDateTime startExchangeTime, LocalDateTime useBatteryTime, int duration,
                          LocalDateTime batteryChargeCompleteTime, String positionNo, int transporFrequency) {
        this(truckNo, 
            BigDecimal.valueOf(soc).setScale(2, RoundingMode.HALF_UP), 
            BigDecimal.valueOf(capacity).setScale(2, RoundingMode.HALF_UP), 
            startAwaitTime, startExchangeTime, useBatteryTime, duration, 
            batteryChargeCompleteTime, positionNo, transporFrequency);
    }

    public String getStartAwaitTimeStr() {
        return startAwaitTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String getStartExchangeTimeStr() {
        return startExchangeTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String getBatteryChargeCompleteTimeStr() {
        return batteryChargeCompleteTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public String getUseBatteryTimeStr() {
        return useBatteryTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    @Override
    public String toString() {
        return "{" +
                "\"truckNo\":\"" + truckNo + "\"," +
                "\"soc\":" + soc.toString() + "," +
                "\"capacity\":\"" + capacity + "\"," +
                "\"startAwaitTime\":\"" + startAwaitTime + "\"," +
                "\"startExchangeTime\":\"" + startExchangeTime + "\"," +
                "\"useBatteryTime\":\"" + useBatteryTime + "\"," +
                "\"duration\":" + duration + "," +
                "\"batteryChargeCompleteTime\":\"" + batteryChargeCompleteTime + "\"," +
                "\"positionNo\":\"" + positionNo + "\"," +
                "\"transporFrequency\":" + transporFrequency +
                "}";
    }
} 