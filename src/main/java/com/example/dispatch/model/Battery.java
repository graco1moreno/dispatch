package com.example.dispatch.model;

import com.example.dispatch.constant.LocationConstants;
import lombok.Data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 电池类
 */
@Data
public class Battery {
    private String positionNo;      // 电池位置编号
    private BigDecimal soc;         // 剩余电量百分比
    private boolean charging;       // 是否在充电中
    private LocalDateTime chargeCompleteTime; // 充满电的时间点
    
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100.0).setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CAPACITY = LocationConstants.DEFAULT_BATTERY_CAPACITY_DECIMAL_KWH;
    private static final BigDecimal CHARGE_RATE = BigDecimal.valueOf(4.7).setScale(2, RoundingMode.HALF_UP);

    public Battery(String positionNo) {
        this.positionNo = positionNo;
        this.soc = BigDecimal.valueOf(100.0).setScale(2, RoundingMode.HALF_UP);  // 初始满电
        this.charging = false;
        // TODO：这里调试使用，后续需要初始设置为当前时间前一小时
        this.chargeCompleteTime = LocalDateTime.of(2025,6,10,10,30,0);
    }

    public boolean isFullyCharged(LocalDateTime currentTime) {
        // 满足以下任一条件，电池可用：
        // 1. 电池已满电且不在充电中
        // 2. 电池正在充电但充电完成时间小于等于当前时间
        return (soc.compareTo(HUNDRED) >= 0 && !charging) || (charging && !chargeCompleteTime.isAfter(currentTime));
    }

    public void startCharging(BigDecimal socLevel, LocalDateTime startTime) {
        this.soc = socLevel.setScale(2, RoundingMode.HALF_UP);
        this.charging = true;
        
        // 计算充电时长: (100 - 剩余soc) * (额定容量 / 100) / 4.7
        BigDecimal socDiff = HUNDRED.subtract(socLevel);
        BigDecimal capacityPer = CAPACITY.divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal chargeDuration = socDiff.multiply(capacityPer).divide(CHARGE_RATE, 2, RoundingMode.HALF_UP);
        int chargeDurationMinutes = chargeDuration.setScale(0, RoundingMode.CEILING).intValue();
        this.chargeCompleteTime = startTime.plusMinutes(chargeDurationMinutes);
    }

    public void completeCharging() {
        this.soc = HUNDRED;
        this.charging = false;
    }

    @Override
    public String toString() {
        return "Battery{" +
                "positionNo='" + positionNo + '\'' +
                ", soc=" + soc +
                ", charging=" + charging +
                ", chargeCompleteTime=" + chargeCompleteTime +
                '}';
    }
} 