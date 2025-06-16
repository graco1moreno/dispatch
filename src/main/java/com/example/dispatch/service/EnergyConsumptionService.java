package com.example.dispatch.service;

import cn.hutool.json.JSONUtil;
import com.example.dispatch.constant.DpConstants;
import com.example.dispatch.model.GeTruckDrivingRecord;
import com.example.dispatch.model.RouteInfo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 能耗统计服务
 * 实现车辆平均单公里能耗计算和存储功能
 */
@Slf4j
@Service
public class EnergyConsumptionService {

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 计算并存储车辆平均单公里能耗
     * @param vehicleNo 车辆编号
     * @param routeInfo 路径信息
     */
    public void calculateAndStoreEnergyConsumption(String vehicleNo, RouteInfo routeInfo) {
        log.info("开始计算车辆 {} 的平均单公里能耗", vehicleNo);
        
        try {
            // 1. 获取30分钟内的历史数据
            List<GeTruckDrivingRecord> historyData = getHistoryData(vehicleNo, 30);
            if (historyData.size() < 50) {
                log.warn("车辆 {} 历史数据不足，无法计算平均能耗", vehicleNo);
                return;
            }
            
            // // 2. 从30分钟数据中筛选最近20分钟的数据进行计算
            // List<GeTruckDrivingRecord> calculationData = getCalculationDataFromLast30Minutes(historyData);
            // if (calculationData.size() < 2) {
            //     log.warn("车辆 {} 20分钟内数据不足，无法计算平均能耗", vehicleNo);
            //     return;
            // }
            //

            // 3. 计算平均单公里能耗
            BigDecimal avgConsumptionPerKm = calculateAverageConsumptionPerKm(historyData);
            if (avgConsumptionPerKm == null || avgConsumptionPerKm.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("车辆 {} 计算出的平均能耗无效：{}", vehicleNo, avgConsumptionPerKm);
                return;
            }
            
            // 4. 判断是否满载
            boolean isLoaded = isVehicleLoaded(routeInfo);
            
            // 5. 检查是否需要更新Redis（35%进度条件）
            if (shouldUpdateConsumption(routeInfo)) {
                storeEnergyConsumption(vehicleNo, isLoaded, avgConsumptionPerKm);
                log.info("车辆 {} 平均单公里能耗已更新：{}kWh/km（满载：{}）", 
                        vehicleNo, avgConsumptionPerKm, isLoaded);
            } else {
                log.info("车辆 {} 当前行驶进度不足35%，不更新能耗数据", vehicleNo);
            }
            
        } catch (Exception e) {
            log.error("计算车辆 {} 平均单公里能耗失败", vehicleNo, e);
        }
    }

    /**
     * 获取历史能耗数据用于SOC计算
     * @param vehicleNo 车辆编号
     * @param isLoaded 是否满载
     * @return 平均单公里能耗（kWh/km），如果没有数据返回null
     */
    public BigDecimal getStoredEnergyConsumption(String vehicleNo, boolean isLoaded) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_CONSUMPTION_PER_KM_KEY + vehicleNo + ":" + isLoaded;
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            String jsonStr = bucket.get();
            
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                log.debug("车辆 {} 没有存储的能耗数据（满载：{}）", vehicleNo, isLoaded);
                return null;
            }
            
            Map<String, Object> consumptionData = JSONUtil.toBean(jsonStr, Map.class);
            Object consumptionObj = consumptionData.get("avgConsumptionPerKm");
            
            if (consumptionObj != null) {
                BigDecimal consumption = new BigDecimal(consumptionObj.toString());
                log.debug("获取车辆 {} 存储的能耗数据：{}kWh/km（满载：{}）", vehicleNo, consumption, isLoaded);
                return consumption;
            }
            
        } catch (Exception e) {
            log.error("获取车辆 {} 存储的能耗数据失败（满载：{}）", vehicleNo, isLoaded, e);
        }
        
        return null;
    }

    /**
     * 获取指定时间窗口内的历史数据
     * @param vehicleNo 车辆编号
     * @param minutes 时间窗口（分钟）
     * @return 历史数据列表
     */
    private List<GeTruckDrivingRecord> getHistoryData(String vehicleNo, int minutes) {
        String redisKey = DpConstants.DP_TRUCK_DRIVING_RECORD + vehicleNo;
        RList<String> redisList = redissonClient.getList(redisKey);
        
        List<GeTruckDrivingRecord> historyData = new ArrayList<>();
        // TODO：这里调试使用，后续需要修改为获取前20分钟的数据
        LocalDateTime now = LocalDateTime.of(2025,6,10,11,46,0);
        LocalDateTime cutoffTime = now.minusMinutes(minutes);

        try {
            List<String> range = redisList.range(-300, -1);
            for (String jsonStr : range) {
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    GeTruckDrivingRecord record = JSONUtil.toBean(jsonStr, GeTruckDrivingRecord.class);
                    
                    if (record != null && record.getReportTime() != null 
                            && record.getReportTime().isAfter(cutoffTime)
                            && record.getTotalPowerConsumption() != null
                            && record.getTotalDrivingKm() != null) {
                        historyData.add(record);
                    }
                }
            }
            
            // 按时间排序
            historyData.sort(Comparator.comparing(GeTruckDrivingRecord::getReportTime));
            return historyData;
        } catch (Exception e) {
            log.error("获取车辆 {} {}分钟内历史数据失败", vehicleNo, minutes, e);
        }
        
        return historyData;
    }

    /**
     * 筛选计算用的数据（指定时间窗口内）
     * @param historyData 历史数据
     * @param minutes 时间窗口（分钟）
     * @return 计算用数据列表
     */
    private List<GeTruckDrivingRecord> getCalculationData(List<GeTruckDrivingRecord> historyData, int minutes) {
        LocalDateTime cutoffTime = LocalDateTime.now().minus(minutes, ChronoUnit.MINUTES);
        
        List<GeTruckDrivingRecord> calculationData = new ArrayList<>();
        for (GeTruckDrivingRecord record : historyData) {
            if (record.getReportTime().isAfter(cutoffTime)) {
                calculationData.add(record);
            }
        }
        
        return calculationData;
    }

    /**
     * 从30分钟历史数据中筛选最近20分钟的数据用于计算
     * @param historyData 30分钟内的历史数据（已按时间排序）
     * @return 最近20分钟的数据列表
     */
    private List<GeTruckDrivingRecord> getCalculationDataFromLast30Minutes(List<GeTruckDrivingRecord> historyData) {
        // TODO：这里调试使用，后续需要修改为获取前20分钟的数据
        LocalDateTime now = LocalDateTime.of(2025,6,10,11,46,0);
        LocalDateTime cutoffTime = now.minusMinutes(30);
        
        List<GeTruckDrivingRecord> calculationData = new ArrayList<>();
        for (GeTruckDrivingRecord record : historyData) {
            if (record.getReportTime().isAfter(cutoffTime)) {
                calculationData.add(record);
            }
        }
        
        log.debug("从 {} 条30分钟历史数据中筛选出 {} 条20分钟数据用于计算", 
                historyData.size(), calculationData.size());
        
        return calculationData;
    }

    /**
     * 计算平均单公里能耗
     * @param data 历史数据列表（按时间排序）
     * @return 平均单公里能耗（kWh/km）
     */
    private BigDecimal calculateAverageConsumptionPerKm(List<GeTruckDrivingRecord> data) {
        if (data.size() < 2) {
            return null;
        }
        
        try {
            // 取第一个和最后一个数据点
            GeTruckDrivingRecord firstRecord = data.get(0);
            GeTruckDrivingRecord lastRecord = data.get(data.size() - 1);
            
            // 计算电耗差值（kWh）
            BigDecimal powerConsumptionDiff = lastRecord.getTotalPowerConsumption()
                    .subtract(firstRecord.getTotalPowerConsumption());
            
            // 计算里程差值（km）
            BigDecimal drivingKmDiff = lastRecord.getTotalDrivingKm()
                    .subtract(firstRecord.getTotalDrivingKm());
            
            // 检查数据有效性
            if (powerConsumptionDiff.compareTo(BigDecimal.ZERO) <= 0 || 
                drivingKmDiff.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("数据异常：电耗差值={}kWh，里程差值={}km", powerConsumptionDiff, drivingKmDiff);
                return null;
            }
            
            // 检查数据合理性：里程差值不应超过500km，电耗差值不应超过700kWh（防止数据重置导致的异常值）
            if (drivingKmDiff.compareTo(new BigDecimal("500")) > 0 || 
                powerConsumptionDiff.compareTo(new BigDecimal("700")) > 0) {
                log.warn("数据可能异常（里程或电耗差值过大）：电耗差值={}kWh，里程差值={}km", 
                        powerConsumptionDiff, drivingKmDiff);
                return null;
            }
            
            // 计算平均单公里能耗
            BigDecimal avgConsumptionPerKm = powerConsumptionDiff.divide(drivingKmDiff, 4, RoundingMode.HALF_UP);
            
            // 检查能耗值的合理性（正常车辆单公里能耗应在0.5-5.0 kWh/km之间）
            if (avgConsumptionPerKm.compareTo(new BigDecimal("0.5")) < 0 || 
                avgConsumptionPerKm.compareTo(new BigDecimal("5.0")) > 0) {
                log.warn("计算出的能耗值可能异常：{}kWh/km，超出合理范围[0.5-5.0]", avgConsumptionPerKm);
                return null;
            }
            
            log.debug("能耗计算：电耗差值={}kWh，里程差值={}km，平均单公里能耗={}kWh/km", 
                    powerConsumptionDiff, drivingKmDiff, avgConsumptionPerKm);
            
            return avgConsumptionPerKm;
            
        } catch (Exception e) {
            log.error("计算平均单公里能耗失败", e);
            return null;
        }
    }

    /**
     * 判断车辆是否满载
     * @param routeInfo 路径信息
     * @return 是否满载
     */
    private boolean isVehicleLoaded(RouteInfo routeInfo) {
        if (routeInfo == null || routeInfo.getCurrentRoute() == null) {
            return false;
        }
        
        // 根据路径类型判断是否满载
        return routeInfo.getCurrentRoute() == RouteInfo.RouteType.LOADING_TO_UNLOADING;
    }

    /**
     * 判断是否应该更新能耗数据（35%进度条件）
     * @param routeInfo 路径信息
     * @return 是否应该更新
     */
    private boolean shouldUpdateConsumption(RouteInfo routeInfo) {
        if (routeInfo == null) {
            log.warn("RouteInfo为空，不允许更新能耗数据");
            return false;
        }
        
        // 判断是否满载
        boolean isLoaded = isVehicleLoaded(routeInfo);
        
        // 检查Redis中是否已有数据
        BigDecimal existingData = getStoredEnergyConsumption(routeInfo.getVehicleNo(), isLoaded);
        
        // 如果没有现有数据，允许新增
        if (existingData == null) {
            log.debug("车辆 {} 无现有能耗数据（满载：{}），允许新增", routeInfo.getVehicleNo(), isLoaded);
            return true;
        }
        
        // 如果已有数据，需要检查行驶进度
        double progressPercentage = routeInfo.getProgressPercentage();
        
        if (progressPercentage > 0.35) {
            log.debug("车辆 {} 行驶进度 {:.1f}% > 35%，允许更新能耗数据", 
                    routeInfo.getVehicleNo(), progressPercentage * 100);
            return true;
        } else {
            log.debug("车辆 {} 行驶进度 {:.1f}% ≤ 35%，不允许更新现有能耗数据", 
                    routeInfo.getVehicleNo(), progressPercentage * 100);
            return false;
        }
    }

    /**
     * 存储能耗数据到Redis
     * @param vehicleNo 车辆编号
     * @param isLoaded 是否满载
     * @param avgConsumptionPerKm 平均单公里能耗
     */
    private void storeEnergyConsumption(String vehicleNo, boolean isLoaded, BigDecimal avgConsumptionPerKm) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_CONSUMPTION_PER_KM_KEY + vehicleNo + ":" + isLoaded;
            
            Map<String, Object> consumptionData = new HashMap<>();
            consumptionData.put("vehicleNo", vehicleNo);
            consumptionData.put("isLoaded", isLoaded);
            consumptionData.put("avgConsumptionPerKm", avgConsumptionPerKm);
            consumptionData.put("updateTime", LocalDateTime.now());
            
            String jsonStr = JSONUtil.toJsonStr(consumptionData);
            
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            bucket.set(jsonStr);
            
            // 设置过期时间为24小时
            bucket.expire(24, TimeUnit.HOURS);
            
            log.info("车辆 {} 能耗数据已存储到Redis：{}", vehicleNo, jsonStr);
            
        } catch (Exception e) {
            log.error("存储车辆 {} 能耗数据失败（满载：{}）", vehicleNo, isLoaded, e);
        }
    }
} 