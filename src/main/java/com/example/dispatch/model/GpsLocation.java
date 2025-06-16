package com.example.dispatch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * GPS位置信息模型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GpsLocation {
    
    /**
     * 纬度 (latitude)
     */
    private double latitude;
    
    /**
     * 经度 (longitude)
     */
    private double longitude;
    
    /**
     * 记录时间戳
     */
    private LocalDateTime timestamp;
    
    /**
     * 车辆编号
     */
    private String vehicleNo;

    /**
     * 地址类型 1-出发点 2-装货点 3-卸货点 4-换电站
     */
    private Integer addrType;

    
    public GpsLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = LocalDateTime.now();
    }
    
    public GpsLocation(double latitude, double longitude, LocalDateTime timestamp) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = timestamp;
    }
    
    /**
     * 计算与另一个GPS位置的距离（使用Haversine公式）
     * @param other 另一个GPS位置
     * @return 距离（米）
     */
    public double distanceTo(GpsLocation other) {
        if (other == null) {
            return Double.MAX_VALUE;
        }
        
        final double EARTH_RADIUS = 6371008.8; // 地球半径（米）
        
        double lat1Rad = Math.toRadians(this.latitude);
        double lat2Rad = Math.toRadians(other.latitude);
        double deltaLatRad = Math.toRadians(other.latitude - this.latitude);
        double deltaLonRad = Math.toRadians(other.longitude - this.longitude);
        
        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS * c;
    }
    
    /**
     * 计算与另一个GPS位置的方位角（度）
     * @param other 目标位置
     * @return 方位角（0-360度）
     */
    public double bearingTo(GpsLocation other) {
        if (other == null) {
            return 0.0;
        }
        
        double lat1Rad = Math.toRadians(this.latitude);
        double lat2Rad = Math.toRadians(other.latitude);
        double deltaLonRad = Math.toRadians(other.longitude - this.longitude);
        
        double y = Math.sin(deltaLonRad) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad);
        
        double bearingRad = Math.atan2(y, x);
        double bearingDeg = Math.toDegrees(bearingRad);
        
        // 转换为0-360度
        return (bearingDeg + 360) % 360;
    }
    
    /**
     * 判断是否在指定位置的范围内
     * @param center 中心位置
     * @param radiusMeters 半径（米）
     * @return 是否在范围内
     */
    public boolean isWithinRadius(GpsLocation center, double radiusMeters) {
        return distanceTo(center) <= radiusMeters;
    }
    
    @Override
    public String toString() {
        return String.format("GpsLocation{lat=%.6f, lon=%.6f, time=%s, vehicle='%s'}", 
                latitude, longitude, timestamp, vehicleNo);
    }
} 