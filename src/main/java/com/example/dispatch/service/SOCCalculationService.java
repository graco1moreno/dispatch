package com.example.dispatch.service;

import com.example.dispatch.constant.LocationConstants;
import com.example.dispatch.model.RouteInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * SOC消耗计算服务
 * 实现完整运输SOC消耗和剩余行驶距离SOC计算
 */
@Slf4j
@Service
public class SOCCalculationService {
    
    @Autowired
    private EnergyConsumptionService energyConsumptionService;
    
    // 车辆能耗参数配置
    private static final double BASE_CONSUMPTION_PER_KM = 1.4;     // 基础能耗：每公里消耗1.4kwh
    private static final double LOAD_FACTOR_LOADED = 1.3;          // 满载时的负荷因子
    private static final double LOAD_FACTOR_EMPTY = 0.72;           // 空载时的负荷因子
    private static final double SPEED_EFFICIENCY_FACTOR = 1.0;     // 速度效率因子
    private static final double TERRAIN_FACTOR = 1.0;              // 地形因子
    private static final double WEATHER_FACTOR = 1.0;              // 天气因子
    private static final double VEHICLE_EFFICIENCY = 0.95;         // 车辆效率
    
    /**
     * 计算完整运输路径的SOC消耗（装货点 -> 卸货点 -> 换电站）
     * @param capacityKwh 电池容量（kWh）
     * @return 总SOC消耗百分比
     */
    public double calculateCompleteTransportSOC(double capacityKwh) {
        return calculateCompleteTransportSOC(capacityKwh, null);
    }
    
    /**
     * 计算完整运输路径的SOC消耗（装货点 -> 卸货点 -> 换电站）
     * @param capacityKwh 电池容量（kWh）
     * @param vehicleNo 车辆编号（用于获取历史能耗数据）
     * @return 总SOC消耗百分比
     */
    public double calculateCompleteTransportSOC(double capacityKwh, String vehicleNo) {
        log.info("开始计算完整运输路径的SOC消耗，电池容量：{}kWh，车辆：{}", capacityKwh, vehicleNo);
        
        try {
            
            // 路径1：装货点到卸货点（满载）
            double loadingToUnloadingSOC = calculateRouteSOC(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM, true, capacityKwh, vehicleNo);
            
            // 路径2：卸货点到换电站（空载）
            double unloadingToChargingSOC = calculateRouteSOC(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM, false, capacityKwh, vehicleNo);
            
            double totalSOC = loadingToUnloadingSOC + unloadingToChargingSOC;
            
            log.info("完整运输SOC消耗：装货到卸货={}%, 卸货到换电={}%, 总计={}%", 
                    loadingToUnloadingSOC, unloadingToChargingSOC, totalSOC);
            
            return totalSOC;
            
        } catch (Exception e) {
            log.error("计算完整运输SOC消耗失败", e);
            return getEstimatedCompleteTransportSOC(capacityKwh, vehicleNo);
        }
    }
    
    /**
     * 计算完整运输路径的SOC消耗（使用RouteInfo中的truck信息）
     * @param routeInfo 路径信息（包含truck）
     * @return 总SOC消耗百分比
     */
    public double calculateCompleteTransportSOC(RouteInfo routeInfo) {
        if (routeInfo.getTruck() == null || routeInfo.getTruck().getCapacity() == null) {
            return calculateCompleteTransportSOC(LocationConstants.DEFAULT_BATTERY_CAPACITY_KWH, routeInfo.getVehicleNo()); // 默认400kWh，传递车辆编号
        }
        return calculateCompleteTransportSOC(routeInfo.getTruck().getCapacity().doubleValue(), routeInfo.getVehicleNo());
    }
    
    /**
     * 向后兼容的方法
     */
    public double calculateCompleteTransportSOC() {
        log.warn("使用默认电池容量计算完整运输SOC");
        return calculateCompleteTransportSOC(LocationConstants.DEFAULT_BATTERY_CAPACITY_KWH); // 默认400kWh
    }
    
    /**
     * 计算本趟剩余行驶公里数对应的SOC消耗
     * @param routeInfo 路径信息
     * @return SOC消耗百分比
     */
    public double calculateRemainingTripSOC(RouteInfo routeInfo) {
        log.info("开始计算车辆 {} 本趟剩余行驶的SOC消耗", routeInfo.getVehicleNo());
        
        try {
            double remainingSOC = 0.0;
            RouteInfo.RouteType currentRoute = routeInfo.getCurrentRoute();
            
            if (currentRoute == RouteInfo.RouteType.START_TO_LOADING) {
                // 一类：从出发点前往装货点
                remainingSOC = calculateStartToLoadingRemainingSOC(routeInfo);
                
            } else if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING) {
                // 二类情况1：车辆从装货点到卸货点
                remainingSOC = calculateLoadingToUnloadingRemainingSOC(routeInfo);
                
            } else if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING_TO_CHARGING) {
                // 新增：车辆从装货点到卸货点再到换电站
                remainingSOC = calculateLoadingToUnloadingChargingRemainingSOC(routeInfo);
                
            } else if (currentRoute == RouteInfo.RouteType.UNLOADING_TO_LOADING) {
                // 二类情况2：车辆从卸货点返程到装货点
                remainingSOC = calculateUnloadingToLoadingRemainingSOC(routeInfo);
                
            } else {
                // 其他情况（如去换电站等）
                remainingSOC = calculateOtherRouteRemainingSOC(routeInfo);
            }
            
            log.info("车辆 {} 本趟剩余SOC消耗：{}%", routeInfo.getVehicleNo(), remainingSOC);
            return remainingSOC;
            
        } catch (Exception e) {
            log.error("计算车辆 {} 本趟剩余SOC消耗失败", routeInfo.getVehicleNo(), e);
            return 5.0; // 默认预估值
        }
    }
    
    /**
     * 判断车辆是否需要前往换电站换电
     * @param currentSOC 当前SOC
     * @param completeTransportSOC 一趟完整运输的SOC消耗
     * @param remainingTripSOC 剩余行驶公里数对应的SOC消耗
     * @return 是否需要换电
     */
    public boolean shouldGoToChargingStation(BigDecimal currentSOC, double completeTransportSOC, double remainingTripSOC) {
        if (currentSOC == null) {
            log.warn("当前SOC为空，建议换电");
            return true;
        }
        
        double currentSOCValue = currentSOC.doubleValue();
        double requiredSOC = completeTransportSOC + remainingTripSOC + LocationConstants.SAFETY_MARGIN_PERCENT;
        
        boolean needCharging = currentSOCValue <= requiredSOC;
        
        log.info("换电判断：当前SOC={}%, 完整运输SOC={}%, 剩余行驶SOC={}%, 安全裕度={}%, 总需求={}%, 需要换电={}",
                currentSOCValue, completeTransportSOC, remainingTripSOC, 
                LocationConstants.SAFETY_MARGIN_PERCENT, requiredSOC, needCharging);
        
        return needCharging;
    }
    
    /**
     * 获取详细的SOC分析报告
     */
    public Map<String, Object> getDetailedSOCAnalysis(RouteInfo routeInfo) {
        Map<String, Object> analysis = new HashMap<>();
        
        try {
            // 基本信息
            analysis.put("vehicleNo", routeInfo.getVehicleNo());
            analysis.put("routeType", routeInfo.getCurrentRoute().getDescription());
            analysis.put("confidence", routeInfo.getConfidence());
            analysis.put("currentSOC", routeInfo.getCurrentSoc() != null ? routeInfo.getCurrentSoc().doubleValue() : 0.0);
            
            // 车辆信息
            if (routeInfo.getTruck() != null) {
                analysis.put("truckNo", routeInfo.getTruck().getTruckNo());
                analysis.put("batteryCapacity", routeInfo.getTruck().getCapacity());
            }
            
            // 距离信息
            analysis.put("totalDistanceKm", routeInfo.getTotalDistanceKm());
            analysis.put("remainingDistanceKm", routeInfo.getRemainingDistanceKm());
            analysis.put("progressPercentage", routeInfo.getProgressPercentage() * 100);
            analysis.put("remainingPercentage", routeInfo.getRemainingPercentage() * 100);
            
            // SOC消耗计算
            double completeTransportSOC = calculateCompleteTransportSOC(routeInfo);
            analysis.put("completeTransportSOC", completeTransportSOC);
            
            double remainingTripSOC = calculateRemainingTripSOC(routeInfo);
            analysis.put("remainingTripSOC", remainingTripSOC);
            
            // 换电判断
            boolean needCharging = shouldGoToChargingStation(
                routeInfo.getCurrentSoc(), completeTransportSOC, remainingTripSOC
            );
            analysis.put("needCharging", needCharging);
            
            // 总需求SOC
            double totalRequiredSOC = completeTransportSOC + remainingTripSOC + LocationConstants.SAFETY_MARGIN_PERCENT;
            analysis.put("totalRequiredSOC", totalRequiredSOC);
            
            log.info("生成SOC详细分析报告：{}", analysis);
            
        } catch (Exception e) {
            log.error("生成SOC分析报告失败", e);
            analysis.put("error", "分析失败：" + e.getMessage());
        }
        
        return analysis;
    }
    
    /**
     * 计算指定距离和负载状态的SOC消耗
     * @param distanceKm 距离（公里）
     * @param isLoaded 是否满载
     * @param capacityKwh 电池容量（kWh）
     * @return SOC消耗百分比
     */
    private double calculateRouteSOC(double distanceKm, boolean isLoaded, double capacityKwh) {
        return calculateRouteSOC(distanceKm, isLoaded, capacityKwh, null);
    }
    
    /**
     * 计算指定距离和负载状态的SOC消耗（支持基于历史数据的计算）
     * @param distanceKm 距离（公里）
     * @param isLoaded 是否满载
     * @param capacityKwh 电池容量（kWh）
     * @param vehicleNo 车辆编号（用于获取历史能耗数据）
     * @return SOC消耗百分比
     */
    public double calculateRouteSOC(double distanceKm, boolean isLoaded, double capacityKwh, String vehicleNo) {
        try {
            // 优先使用历史能耗数据计算
            if (vehicleNo != null) {
                BigDecimal historicalConsumption = energyConsumptionService.getStoredEnergyConsumption(vehicleNo, isLoaded);
                if (historicalConsumption != null && historicalConsumption.compareTo(BigDecimal.ZERO) > 0) {
                    // 使用历史数据计算：距离 × 历史平均单公里能耗 ÷ 电池容量 × 100
                    double consumptionKwh = distanceKm * historicalConsumption.doubleValue();
                    double socPercentage = (consumptionKwh / capacityKwh) * 100.0;
                    
                    log.debug("使用历史能耗数据计算SOC：距离={}km, 历史能耗={}kWh/km, 电量消耗={}kWh, 容量={}kWh, SOC消耗={}%",
                            distanceKm, historicalConsumption, consumptionKwh, capacityKwh, socPercentage);
                    
                    return Math.max(0.0, socPercentage);
                }
            }
            
            // 回退到原有的计算逻辑
            log.debug("使用原有计算逻辑：车辆{}没有历史能耗数据或数据无效", vehicleNo);
            
        } catch (Exception e) {
            log.warn("使用历史能耗数据计算失败，回退到原有逻辑：{}", e.getMessage());
        }
        
        // 原有的计算逻辑
        // 1. 计算各影响因子
        double loadFactor = isLoaded ? LOAD_FACTOR_LOADED : LOAD_FACTOR_EMPTY;
        double environmentFactor = SPEED_EFFICIENCY_FACTOR * TERRAIN_FACTOR * WEATHER_FACTOR;
        
        // 2. 基础能耗计算（实际电量消耗，单位：kWh）
        double baseConsumptionKwh = distanceKm * BASE_CONSUMPTION_PER_KM;
        
        // 3. 应用各种影响因子得到调整后的电量消耗
        double adjustedConsumptionKwh = baseConsumptionKwh * loadFactor * environmentFactor;
        
        // 4. 转换为SOC百分比：adjustedConsumptionSOC / RouteInfo.truck.capacity
        double socPercentage = (adjustedConsumptionKwh / capacityKwh) * 100.0;
        
        log.debug("原有SOC计算：距离={}km, 负荷因子={}, 环境因子={}, 电量消耗={}kWh, 容量={}kWh, SOC消耗={}%",
                distanceKm, loadFactor, environmentFactor, adjustedConsumptionKwh, capacityKwh, socPercentage);
        
        return Math.max(0.0, socPercentage); // 确保非负值
    }
    
    /**
     * 计算从出发点到装货点的剩余SOC消耗
     */
    private double calculateStartToLoadingRemainingSOC(RouteInfo routeInfo) {
        // 剩余未行驶的路程占比 * 当前路线（出发点到装货点）的公里数
        double remainingPercentage = routeInfo.getRemainingPercentage();
        double routeDistanceKm = LocationConstants.START_TO_LOADING_DISTANCE_KM;
        double remainingDistanceKm = remainingPercentage * routeDistanceKm;
        
        double capacityKwh = getTruckCapacity(routeInfo);
        return calculateRouteSOC(remainingDistanceKm, false, capacityKwh, routeInfo.getVehicleNo()); // 空载，传递车辆编号
    }
    
    /**
     * 计算从装货点到卸货点的剩余SOC消耗
     */
    public double calculateLoadingToUnloadingRemainingSOC(RouteInfo routeInfo) {
        // 当前路线剩余 + 返程（卸货点到装货点）
        double remainingPercentage = routeInfo.getRemainingPercentage();
        double currentRouteDistanceKm = LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM;
        double currentRemainingKm = remainingPercentage * currentRouteDistanceKm;
        
        double capacityKwh = getTruckCapacity(routeInfo);
        
        // 当前路线剩余（满载）
        double currentRemainingSOC = calculateRouteSOC(currentRemainingKm, true, capacityKwh, routeInfo.getVehicleNo());

        // 返程（卸货点返回装货点，空载）
        double returnTripDistanceKm = LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM +
                                    LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM;
        double returnTripSOC = calculateRouteSOC(returnTripDistanceKm, false, capacityKwh, routeInfo.getVehicleNo());
        
        return currentRemainingSOC + returnTripSOC;
    }
    
    /**
     * 计算从卸货点返程到装货点的剩余SOC消耗
     */
    public double calculateUnloadingToLoadingRemainingSOC(RouteInfo routeInfo) {
        // 剩余未行驶的路程占比 * 当前路线（卸货点返程到装货点）的公里数
        double remainingPercentage = routeInfo.getRemainingPercentage();
        double routeDistanceKm = LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM;
        double remainingDistanceKm = remainingPercentage * routeDistanceKm;
        
        double capacityKwh = getTruckCapacity(routeInfo);
        return calculateRouteSOC(remainingDistanceKm, false, capacityKwh, routeInfo.getVehicleNo()); // 空载，传递车辆编号
    }
    
    /**
     * 计算从装货点到卸货点再到换电站的剩余SOC消耗
     */
    public double calculateLoadingToUnloadingChargingRemainingSOC(RouteInfo routeInfo) {
        log.debug("计算车辆 {} LOADING_TO_UNLOADING_CHARGING路径的剩余SOC消耗", routeInfo.getVehicleNo());
        
        double remainingPercentage = routeInfo.getRemainingPercentage();
        double capacityKwh = getTruckCapacity(routeInfo);
        
        // 路径总距离 = 装货点到卸货点 + 卸货点到换电站
        double totalRouteDistanceKm = LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM + 
                                    LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM;
        
        // 计算当前剩余总距离
        double remainingTotalDistanceKm = remainingPercentage * totalRouteDistanceKm;
        
        double remainingSOC = 0.0;
        
        // 判断当前位置：如果剩余距离大于卸货点到换电站的距离，说明还在装货点到卸货点段
        if (remainingTotalDistanceKm > LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM) {
            // 当前还在装货点到卸货点段（满载）
            double loadedSegmentRemainingKm = remainingTotalDistanceKm - LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM;
            double loadedSegmentSOC = calculateRouteSOC(loadedSegmentRemainingKm, true, capacityKwh, routeInfo.getVehicleNo());

            // 卸货点到换电站段（空载，全程）
            double emptySegmentSOC = calculateRouteSOC(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM, false, capacityKwh, routeInfo.getVehicleNo());

            remainingSOC = loadedSegmentSOC + emptySegmentSOC;

            log.debug("车辆 {} 处于满载段，满载剩余距离={}km，满载SOC={}%，空载段SOC={}%，总计={}%",
                    routeInfo.getVehicleNo(), loadedSegmentRemainingKm, loadedSegmentSOC, emptySegmentSOC, remainingSOC);
        } else {
            // 当前已在卸货点到换电站段（空载）
            remainingSOC = calculateRouteSOC(remainingTotalDistanceKm, false, capacityKwh, routeInfo.getVehicleNo());

            log.debug("车辆 {} 处于空载段，剩余距离={}km，SOC={}%",
                    routeInfo.getVehicleNo(), remainingTotalDistanceKm, remainingSOC);
        }
        
        return remainingSOC;
    }
    
    /**
     * 计算其他路线的剩余SOC消耗
     */
    public double calculateOtherRouteRemainingSOC(RouteInfo routeInfo) {
        double remainingPercentage = routeInfo.getRemainingPercentage();
        double routeDistanceKm = routeInfo.getTotalDistanceKm();
        double remainingDistanceKm = remainingPercentage * routeDistanceKm;
        
        // 根据路线类型判断是否满载
        boolean isLoaded = (routeInfo.getCurrentRoute() == RouteInfo.RouteType.LOADING_TO_UNLOADING ||
                           routeInfo.getCurrentRoute() == RouteInfo.RouteType.LOADING_TO_UNLOADING_TO_CHARGING);
        
        double capacityKwh = getTruckCapacity(routeInfo);
        return calculateRouteSOC(remainingDistanceKm, isLoaded, capacityKwh, routeInfo.getVehicleNo()); // 传递车辆编号
    }
    
    /**
     * 从RouteInfo获取truck容量，如果没有则使用默认值
     */
    private double getTruckCapacity(RouteInfo routeInfo) {
        if (routeInfo.getTruck() != null && routeInfo.getTruck().getCapacity() != null) {
            return routeInfo.getTruck().getCapacity().doubleValue();
        } else {
            log.warn("RouteInfo中缺少truck容量信息，使用默认值{}kWh", LocationConstants.DEFAULT_BATTERY_CAPACITY_KWH);
            return LocationConstants.DEFAULT_BATTERY_CAPACITY_KWH;
        }
    }
    
    /**
     * 获取预估的完整运输SOC消耗（当计算失败时使用）
     */
    private double getEstimatedCompleteTransportSOC(double capacityKwh, String vehicleNo) {
        
        double loadedDistanceSOC = calculateRouteSOC(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM, true, capacityKwh, vehicleNo);
        double emptyDistanceSOC = calculateRouteSOC(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM, false, capacityKwh, vehicleNo);
        
        double estimatedSOC = loadedDistanceSOC + emptyDistanceSOC;
        
        log.warn("使用预估的完整运输SOC消耗：{}%", estimatedSOC);
        return estimatedSOC;
    }
} 