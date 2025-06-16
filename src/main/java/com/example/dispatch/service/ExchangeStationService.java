package com.example.dispatch.service;

import com.example.dispatch.constant.LocationConstants;
import com.example.dispatch.model.Battery;
import com.example.dispatch.model.ExchangeRecord;
import com.example.dispatch.model.Truck;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 换电站服务类
 */
public class ExchangeStationService {
    private final Map<String, Battery> batteries;  // 电池位置映射
    private final Queue<Truck> waitingQueue;       // 等待换电队列
    private final List<ExchangeRecord> exchangeRecords; // 换电记录
    private LocalDateTime lastExchangeEndTime;     // 上一次换电结束时间
    private boolean isExchanging;                  // 是否有车辆正在换电
    
    private static final BigDecimal ENERGY_CONSUMPTION = BigDecimal.valueOf(1.4).setScale(2, RoundingMode.HALF_UP); // 综合平均能耗(kWh/km)
    private static final BigDecimal MIN_EXCHANGE_SOC = BigDecimal.valueOf(52.70).setScale(2, RoundingMode.HALF_UP); // 最低换电SOC
    // private static final BigDecimal MIN_EXCHANGE_SOC = BigDecimal.valueOf(40.1).setScale(2, RoundingMode.HALF_UP); // 最低换电SOC
    private static final int EXCHANGE_DURATION = 5;       // 换电时长(分钟)
    private static final BigDecimal CHARGE_RATE = BigDecimal.valueOf(4.7).setScale(2, RoundingMode.HALF_UP);        // 每分钟充电量(kWh)
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100.0).setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal CAPACITY = LocationConstants.DEFAULT_BATTERY_CAPACITY_DECIMAL_KWH;

    public ExchangeStationService(int batteryNum) {
        this.batteries = new HashMap<>();
        this.waitingQueue = new LinkedList<>();
        this.exchangeRecords = new ArrayList<>();
        // TODO：这里调试使用，后续需要初始设置为当前时间前一小时
        this.lastExchangeEndTime = LocalDateTime.of(2025,6,10,10,30,0);
        this.isExchanging = false;
        
        // 初始化电池位置
        for (int i = 1; i <= batteryNum; i++) {
            batteries.put("no" + i, new Battery("no" + i));
        }
    }

    public LocalDateTime getLastExchangeEndTime() {
        return lastExchangeEndTime;
    }

    /**
     * 检查是否有可用电池
     * @param currentTime 当前时间
     * @return 是否有可用电池
     */
    public boolean hasAvailableBattery(LocalDateTime currentTime) {
        return !getAvailableBatteries(currentTime).isEmpty();
    }

    /**
     * 车辆进入换电站等待换电
     * @param truck 需要换电的车辆
     * @param currentTime 当前时间
     */
    public void enterStation(Truck truck, LocalDateTime currentTime) {
        // 将车辆加入等待队列
        truck.setStartAwaitTime(currentTime);
        waitingQueue.offer(truck);
        
        // 如果当前没有车辆在换电，则尝试处理换电
        if (!isExchanging) {
            processExchange(currentTime);
        }
    }

    /**
     * 处理换电逻辑
     * @param currentTime 当前时间
     */
    public void processExchange(LocalDateTime currentTime) {
        // 如果没有等待换电的车辆，直接返回
        if (waitingQueue.isEmpty()) {
            isExchanging = false;
            return;
        }
        
        // 如果当前有车辆在换电，直接返回
        if (isExchanging) {
            return;
        }
        
        // 获取可用的电池
        List<Battery> availableBatteries = getAvailableBatteries(currentTime);
        
        // 如果没有立即可用的电池，检查最早可用的电池
        if (availableBatteries.isEmpty()) {
            // 获取所有电池（包括正在充电的）并按充满时间排序
            List<Battery> allBatteries = new ArrayList<>(batteries.values());
            allBatteries.sort(Comparator.comparing(Battery::getChargeCompleteTime));
            
            if (!allBatteries.isEmpty()) {
                // 获取最早充满的电池
                Battery earliestBattery = allBatteries.get(0);
                // 更新当前时间为最早充满时间
                currentTime = earliestBattery.getChargeCompleteTime().isAfter(currentTime) ? 
                    earliestBattery.getChargeCompleteTime() : currentTime;
                // 重新检查是否有可用电池
                availableBatteries = getAvailableBatteries(currentTime);
            }
        }
        
        // 如果仍然没有可用电池，直接返回
        if (availableBatteries.isEmpty()) {
            return;
        }
        
        // 获取队列中的第一辆车进行换电
        Truck truck = waitingQueue.poll();
        if (truck != null) {
            // 标记开始换电
            isExchanging = true;

            // 选择最早可用的电池
            Battery availableBattery = availableBatteries.get(0);
            
            // 计算换电时间
            LocalDateTime exchangeStartTime = currentTime.isAfter(lastExchangeEndTime) ? currentTime : lastExchangeEndTime;
            // 如果电池还在充电，等待电池充满
            exchangeStartTime = availableBattery.getChargeCompleteTime().isAfter(exchangeStartTime) ?
                availableBattery.getChargeCompleteTime() : exchangeStartTime;
            
            LocalDateTime exchangeEndTime = exchangeStartTime.plusMinutes(EXCHANGE_DURATION);
            
            // 计算充电时长
            int chargeDuration = calculateChargeDuration(truck.getSoc());
            
            // 创建换电记录
            ExchangeRecord record = new ExchangeRecord(
                truck.getTruckNo(),
                truck.getSoc(),
                truck.getCapacity(),
                truck.getStartAwaitTime(),
                exchangeStartTime,
                availableBattery.getChargeCompleteTime(),
                chargeDuration,
                exchangeEndTime.plusMinutes(chargeDuration),
                availableBattery.getPositionNo(),
                truck.getTransportFrequency()
            );
            
            // 添加到换电记录列表
            exchangeRecords.add(record);
            
            // 更新电池状态
            availableBattery.startCharging(truck.getSoc(), exchangeEndTime);
            
            // 更新车辆SOC
            truck.setSoc(HUNDRED);
            
            // 更新上一次换电结束时间
            lastExchangeEndTime = exchangeEndTime;
            
            // 标记换电结束
            isExchanging = false;
            
            // 继续处理下一辆车
            processExchange(exchangeEndTime);
        }
    }

    /**
     * 获取所有满电的可用电池
     * @param currentTime 当前时间
     * @return 可用的电池列表，按充满时间排序
     */
    private List<Battery> getAvailableBatteries(LocalDateTime currentTime) {
        List<Battery> availableBatteries = new ArrayList<>();
        
        // 查找所有已满电的电池或正在充电的电池，或已经充满的电池且当前时间大于等于充满时间
        for (Battery battery : batteries.values()) {
            if (battery.isFullyCharged(currentTime) || !battery.getChargeCompleteTime().isAfter(currentTime)) {
                availableBatteries.add(battery);
            }
        }
        
        // 按照电池充满时间排序
        availableBatteries.sort(Comparator.comparing(Battery::getChargeCompleteTime));
        
        return availableBatteries;
    }
    
    /**
     * 获取最早充满的电池
     * @return 最早充满的电池
     */
    private Battery getEarliestChargingBattery() {
        List<Battery> chargingBatteries = new ArrayList<>();
        
        // 收集所有正在充电的电池
        for (Battery battery : batteries.values()) {
            if (battery.isCharging()) {
                chargingBatteries.add(battery);
            }
        }
        
        // 按照电池充满时间排序
        chargingBatteries.sort(Comparator.comparing(Battery::getChargeCompleteTime));
        
        // 返回最早充满的电池，如果没有正在充电的电池则返回null
        return chargingBatteries.isEmpty() ? null : chargingBatteries.get(0);
    }

    /**
     * 计算电池充电时长
     * @param soc 电池剩余电量百分比
     * @return 充电时长(分钟)
     */
    private int calculateChargeDuration(BigDecimal soc) {
        // 计算充电时长: (100 - 剩余soc) * (额定容量 / 100) / 4.7
        BigDecimal socDiff = HUNDRED.subtract(soc);
        BigDecimal capacityPer = CAPACITY.divide(HUNDRED, 2, RoundingMode.HALF_UP);
        BigDecimal chargeDuration = socDiff.multiply(capacityPer).divide(CHARGE_RATE, 2, RoundingMode.HALF_UP);
        return chargeDuration.setScale(0, RoundingMode.CEILING).intValue();
    }

    /**
     * 检查电池是否都已充满
     * @param currentTime 当前时间
     * @return true: 所有电池都已充满; false: 至少有一块电池在充电中
     */
    public boolean allBatteriesFullyCharged(LocalDateTime currentTime) {
        for (Battery battery : batteries.values()) {
            if (battery.isCharging() && battery.getChargeCompleteTime().isAfter(currentTime)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取最低换电SOC（固定阈值）
     * @return 最低换电SOC
     */
    public static BigDecimal getMinExchangeSoc() {
        return MIN_EXCHANGE_SOC;
    }
    
    /**
     * 根据距离动态计算最低换电SOC
     * @param distanceKm 距离(公里)
     * @return 动态计算的最低换电SOC
     */
    public static BigDecimal getMinExchangeSoc(double distanceKm) {
        return getMinExchangeSoc(BigDecimal.valueOf(distanceKm));
    }
    
    /**
     * 根据距离动态计算最低换电SOC（高精度版本）
     * @param distanceKm 距离(公里)
     * @return 动态计算的最低换电SOC
     */
    public static BigDecimal getMinExchangeSoc(BigDecimal distanceKm) {
        // 计算完成该距离运输所需的SOC百分比
        BigDecimal requiredSoc = distanceKm.multiply(ENERGY_CONSUMPTION)
                .divide(CAPACITY, 2, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP);
        
        // 添加安全边际: 所需SOC + 10% + 距离基于的边际
        BigDecimal safetyMargin = calculateSafetyMargin(distanceKm);
        return requiredSoc.add(safetyMargin).min(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * 计算安全边际
     * @param distance 距离(km)
     * @return 安全边际(百分比)
     */
    private static BigDecimal calculateSafetyMargin(BigDecimal distance) {
        // 基础安全边际: 10%
        BigDecimal baseMargin = BigDecimal.TEN;
        
        // 距离相关边际: 距离越长，额外边际越大
        // 小于等于30km: 额外+0%
        // 30-60km: 额外+5%
        // 60-100km: 额外+10%
        // 100km以上: 额外+15%
        BigDecimal additionalMargin;
        
        if (distance.compareTo(BigDecimal.valueOf(30)) <= 0) {
            additionalMargin = BigDecimal.ZERO;
        } else if (distance.compareTo(BigDecimal.valueOf(60)) <= 0) {
            additionalMargin = BigDecimal.valueOf(5);
        } else if (distance.compareTo(BigDecimal.valueOf(100)) <= 0) {
            additionalMargin = BigDecimal.valueOf(10);
        } else {
            additionalMargin = BigDecimal.valueOf(15);
        }
        
        return baseMargin.add(additionalMargin);
    }

    /**
     * 获取换电记录
     * @return 换电记录列表
     */
    public List<ExchangeRecord> getExchangeRecords() {
        // 验证和修正记录中的时间关系
        validateAndCorrectRecords();
        return exchangeRecords;
    }

    /**
     * 验证并修正换电记录中的时间关系
     * 确保时间关系满足: 开始等待时间 <= 开始换电时间 <= 使用电池时间 <= 电池充满时间
     */
    private void validateAndCorrectRecords() {
        for (ExchangeRecord record : exchangeRecords) {
            // 确保开始换电时间不早于开始等待时间
            if (record.getStartExchangeTime().isBefore(record.getStartAwaitTime())) {
                record.setStartExchangeTime(record.getStartAwaitTime());
            }
            
            // 确保使用电池时间不早于开始换电时间
            if (record.getUseBatteryTime().isBefore(record.getStartExchangeTime())) {
                record.setUseBatteryTime(record.getStartExchangeTime());
            }
            
            // 确保电池充满时间不早于使用电池时间
            if (record.getBatteryChargeCompleteTime().isBefore(record.getUseBatteryTime())) {
                record.setBatteryChargeCompleteTime(
                    record.getUseBatteryTime().plusMinutes(record.getDuration())
                );
            }
        }
    }

    /**
     * 检查是否可以提前换电（低峰时段主动换电）
     * 条件: 有可用电池 + 等待队列不长
     * @param currentTime 当前时间
     * @return 是否建议提前换电
     */
    public boolean canExchangeEarly(LocalDateTime currentTime) {
        // 检查是否有可用电池
        boolean hasAvailable = hasAvailableBattery(currentTime);
        
        // 检查等待队列长度（不超过1辆车等待）
        boolean shortQueue = waitingQueue.size() <= 1;
        
        return hasAvailable && shortQueue;
    }
} 