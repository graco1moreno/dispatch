package com.example.dispatch.model;

import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 电动车类
 */
@Data
public class Truck {
    private String truckNo;        // 车牌号
    private BigDecimal soc;        // 剩余电量百分比
    private BigDecimal capacity;   // 电池额定容量(kWh)
    private int transportFrequency; // 运输次数
    private LocalDateTime startAwaitTime;     // 进站等待换电时间

    public Truck(String truckNo, double soc, double capacity) {
        this.truckNo = truckNo;
        this.soc = BigDecimal.valueOf(soc).setScale(2, RoundingMode.HALF_UP);
        this.capacity = BigDecimal.valueOf(capacity).setScale(2, RoundingMode.HALF_UP);
        this.transportFrequency = 0;
    }
    
    public Truck(String truckNo, BigDecimal soc, BigDecimal capacity) {
        this.truckNo = truckNo;
        this.soc = soc.setScale(2, RoundingMode.HALF_UP);
        this.capacity = capacity.setScale(2, RoundingMode.HALF_UP);
        this.transportFrequency = 0;
    }

    public void incrementTransportFrequency() {
        this.transportFrequency++;
    }

    @Override
    public String toString() {
        return "Truck{" +
                "truckNo='" + truckNo + '\'' +
                ", soc=" + soc +
                ", capacity=" + capacity +
                ", transportFrequency=" + transportFrequency +
                '}';
    }
} 