package com.example.dispatch.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 路径信息模型
 */
@Data
public class RouteInfo {
    
    /**
     * 路径类型枚举
     */
    public enum RouteType {
        START_TO_LOADING("出发点到装货点"),
        LOADING_TO_UNLOADING("装货点到卸货点"),
        LOADING_TO_UNLOADING_TO_CHARGING("装货点到卸货点再到换电站"),
        UNLOADING_TO_CHARGING("卸货点到换电站"),
        CHARGING_TO_LOADING("换电站到装货点"),
        UNLOADING_TO_LOADING("卸货点返程到装货点"),


        UNKNOWN("未知路径");
        
        private final String description;
        
        RouteType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    /**
     * 车辆编号
     */
    private String vehicleNo;
    
    /**
     * 当前路径类型
     */
    private RouteType currentRoute;
    
    /**
     * 起始位置
     */
    private String startLocation;
    
    /**
     * 目标位置
     */
    private String targetLocation;
    
    /**
     * 当前位置坐标
     */
    private GeTruckDrivingRecord currentLocation;
    
    /**
     * 路径总距离（公里）
     */
    private double totalDistanceKm;
    
    /**
     * 已行驶距离（公里）
     */
    private double traveledDistanceKm;
    
    /**
     * 剩余距离（公里）
     */
    private double remainingDistanceKm;
    
    /**
     * 剩余路程占比（0-1）
     */
    private double remainingPercentage;
    
    /**
     * 路径开始时间
     */
    private LocalDateTime routeStartTime;
    
    /**
     * 预计到达时间
     */
    private LocalDateTime estimatedArrivalTime;
    
    /**
     * 历史轨迹点（前20分钟）
     */
    private List<GeTruckDrivingRecord> historyTrack;
    
    /**
     * 当前速度（km/h）
     */
    private double currentSpeed;
    
    /**
     * 平均速度（km/h）
     */
    private double averageSpeed;
    
    /**
     * 路径置信度（0-1，表示路径判断的准确性）
     */
    private double confidence;
    
    /**
     * 当前车辆SOC（%）
     */
    private BigDecimal currentSoc;
    
    /**
     * 关联的车辆信息
     */
    private Truck truck;
    
    /**
     * 计算剩余路程占比
     */
    public void calculateRemainingPercentage() {
        if (totalDistanceKm > 0) {
            this.remainingPercentage = remainingDistanceKm / totalDistanceKm;
        } else {
            this.remainingPercentage = 0.0;
        }
    }
    
    /**
     * 计算当前进度占比
     */
    public double getProgressPercentage() {
        return 1.0 - remainingPercentage;
    }
    
    /**
     * 判断是否接近目标点
     * @param thresholdKm 阈值距离（公里）
     * @return 是否接近
     */
    public boolean isNearTarget(double thresholdKm) {
        return remainingDistanceKm <= thresholdKm;
    }
    
    @Override
    public String toString() {
        return String.format("RouteInfo{vehicle='%s', route=%s, progress=%.1f%%, remaining=%.2fkm, confidence=%.2f, currentSOC=%s%%}", 
                vehicleNo, currentRoute.getDescription(), getProgressPercentage() * 100, 
                remainingDistanceKm, confidence, currentSoc);
    }
} 