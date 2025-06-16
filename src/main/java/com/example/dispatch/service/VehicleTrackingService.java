package com.example.dispatch.service;

import cn.hutool.json.JSONUtil;
import com.example.dispatch.constant.DpConstants;
import com.example.dispatch.constant.LocationConstants;
import com.example.dispatch.model.GeTruckDrivingRecord;
import com.example.dispatch.model.RouteInfo;
import com.example.dispatch.model.Truck;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 车辆轨迹分析服务
 * 实现路径判断逻辑和剩余路程计算
 */
@Slf4j
@Service
public class VehicleTrackingService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private EnergyConsumptionService energyConsumptionService;
    
    /**
     * 分析车辆当前路径状态
     *
     * @param vehicleNo     车辆编号
     * @param truckRouteMap
     * @return 路径信息
     */
    public RouteInfo analyzeVehicleRoute(String vehicleNo, Map<String, RouteInfo> truckRouteMap) {
        RouteInfo routeInfo = Objects.isNull(truckRouteMap) ? null : truckRouteMap.get(vehicleNo);
        if (Objects.nonNull(routeInfo)) {
            return routeInfo;
        }

        log.info("开始分析车辆 {} 的路径状态", vehicleNo);
        
        try {
            // 1. 获取车辆当前状态
            GeTruckDrivingRecord currentStatus = getVehicleCurrentStatus(vehicleNo);
            if (currentStatus == null) {
                log.warn("无法获取车辆 {} 的当前状态", vehicleNo);
                return createDefaultRouteInfo(vehicleNo);
            }
            
            // 2. 获取历史轨迹数据（前20分钟）
            List<GeTruckDrivingRecord> historyTrack = getVehicleHistoryTrack(vehicleNo);
            
            // 3. 分析路径方向和类型
            routeInfo = analyzeRouteDirection(vehicleNo, currentStatus, historyTrack);

            // 4. 设置当前SOC信息
            routeInfo.setCurrentSoc(currentStatus.getSoc());
            
            // 5. 计算剩余路程和占比
            calculateRemainingDistance(routeInfo, currentStatus);
            
            // 6. 计算置信度
            calculateRouteConfidence(routeInfo);
            
            // 7. 计算并存储车辆平均单公里能耗
            energyConsumptionService.calculateAndStoreEnergyConsumption(vehicleNo, routeInfo);
            
            log.info("车辆 {} 路径分析完成：{}", vehicleNo, routeInfo);
            return routeInfo;
            
        } catch (Exception e) {
            log.error("分析车辆 {} 路径状态失败", vehicleNo, e);
            return createDefaultRouteInfo(vehicleNo);
        }
    }
    
    /**
     * 获取车辆当前状态
     * @param vehicleNo 车辆编号
     * @return 车辆状态记录
     */
    private GeTruckDrivingRecord getVehicleCurrentStatus(String vehicleNo) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_STATUS_KEY + vehicleNo;
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            String jsonStr = bucket.get();
            
            if (jsonStr == null || jsonStr.trim().isEmpty()) {
                log.warn("车辆 {} 在Redis中没有当前状态数据", vehicleNo);
                return null;
            }
            
            // 解析JSON字符串为GeTruckDrivingRecord对象
            GeTruckDrivingRecord record = JSONUtil.toBean(jsonStr, GeTruckDrivingRecord.class);
            log.debug("获取车辆 {} 当前状态：位置({}, {}), SOC={}%", 
                    vehicleNo, record.getLat(), record.getLon(), record.getSoc());
            
            return record;
            
        } catch (Exception e) {
            log.error("获取车辆 {} 当前状态失败", vehicleNo, e);
            return null;
        }
    }
    
    /**
     * 从Redis获取车辆历史轨迹（前20分钟）
     */
    private List<GeTruckDrivingRecord> getVehicleHistoryTrack(String vehicleNo) {
        String redisKey = DpConstants.DP_TRUCK_DRIVING_RECORD + vehicleNo;
        RList<String> redisList = redissonClient.getList(redisKey);
        
        List<GeTruckDrivingRecord> historyTrack = new ArrayList<>();

        // TODO：这里调试使用，后续需要修改为获取前30分钟的数据
        LocalDateTime now = LocalDateTime.of(2025,6,10,11,46,0);
        LocalDateTime cutoffTime = now.minusMinutes(LocationConstants.HISTORY_TRACK_WINDOW_MINUTES);


        try {
            List<String> range = redisList.range(-300, -1);
            for (String jsonStr : range) {
                if (jsonStr != null && !jsonStr.trim().isEmpty()) {
                    GeTruckDrivingRecord record = JSONUtil.toBean(jsonStr, GeTruckDrivingRecord.class);
                    
                    // 只保留前20分钟的数据
                    if (record != null && record.getReportTime() != null && record.getReportTime().isAfter(cutoffTime)) {
                        historyTrack.add(record);
                    }
                }
            }
            
            // 按时间排序
            historyTrack.sort(Comparator.comparing(GeTruckDrivingRecord::getReportTime));
            log.info("获取到车辆 {} 的历史轨迹点数量：{}", vehicleNo, historyTrack.size());
            
        } catch (Exception e) {
            log.error("获取车辆 {} 历史轨迹失败", vehicleNo, e);
        }
        
        return historyTrack;
    }
    
    /**
     * 分析路径方向和类型（核心算法）
     */
    private RouteInfo analyzeRouteDirection(String vehicleNo, GeTruckDrivingRecord currentStatus, 
                                          List<GeTruckDrivingRecord> historyTrack) {
        RouteInfo routeInfo = new RouteInfo();
        routeInfo.setVehicleNo(vehicleNo);
        routeInfo.setCurrentLocation(currentStatus);
        routeInfo.setHistoryTrack(historyTrack);

        Truck truck = getTruckInfo(vehicleNo);
        routeInfo.setTruck(truck);

        if (historyTrack.isEmpty()) {
            // 没有历史数据，默认从出发点到装货点
            log.info("车辆 {} 无历史轨迹数据，默认从出发点到装货点", vehicleNo);
            routeInfo.setCurrentRoute(RouteInfo.RouteType.START_TO_LOADING);
            routeInfo.setStartLocation("START");
            routeInfo.setTargetLocation("LOADING");
            routeInfo.setTotalDistanceKm(LocationConstants.START_TO_LOADING_DISTANCE_KM);
            return routeInfo;
        }

        GeTruckDrivingRecord first = historyTrack.get(0);
        GeTruckDrivingRecord last = historyTrack.get(historyTrack.size() - 1);

        long millis = Duration.between(first.getReportTime(), last.getReportTime()).toMillis();
        if (millis <= LocationConstants.START_TO_LOADING_DISTANCE_DURATION) {
            log.info("车辆{}行驶记录时长小于20分钟，默认从出发点到装货点", vehicleNo);
            routeInfo.setCurrentRoute(RouteInfo.RouteType.START_TO_LOADING);
            routeInfo.setStartLocation("START");
            routeInfo.setTargetLocation("LOADING");
            routeInfo.setTotalDistanceKm(LocationConstants.START_TO_LOADING_DISTANCE_KM);
            return routeInfo;
        }

        // 使用轨迹分析算法
        RouteInfo.RouteType detectedRoute = detectRouteFromTrajectory(currentStatus, historyTrack);
        routeInfo.setCurrentRoute(detectedRoute);
        
        // 设置起始和目标位置
        setRouteStartAndTarget(routeInfo, detectedRoute);
        
        return routeInfo;
    }
    
    /**
     * 基于轨迹的路径检测算法
     */
    private RouteInfo.RouteType detectRouteFromTrajectory(GeTruckDrivingRecord currentStatus, 
                                                        List<GeTruckDrivingRecord> historyTrack) {
        
        // 1. 分析轨迹段的位置类型
        List<String> locationSequence = analyzeLocationSequence(historyTrack);
        String currentLocation = identifyLocation(currentStatus);
        locationSequence.add(currentLocation);
        
        // 2. 基于位置序列推断路径类型
        RouteInfo.RouteType routeType = inferRouteFromLocationSequence(locationSequence);
        
        // 3. 使用方向向量验证路径
        if (historyTrack.size() >= 2) {
            routeType = validateRouteWithDirectionVector(routeType, currentStatus, historyTrack);
        }
        
        log.info("轨迹分析结果 - 位置序列：{}，推断路径：{}", locationSequence, routeType);
        return routeType;
    }
    
    /**
     * 分析位置序列
     */
    private List<String> analyzeLocationSequence(List<GeTruckDrivingRecord> historyTrack) {
        List<String> sequence = new ArrayList<>();
        
        for (GeTruckDrivingRecord record : historyTrack) {
            String locationType = identifyLocation(record);
            
            // 避免连续重复的位置类型
            if (sequence.isEmpty() || !sequence.get(sequence.size() - 1).equals(locationType)) {
                sequence.add(locationType);
            }
        }
        
        return sequence;
    }
    
    /**
     * 判断车辆位置属于哪个关键点
     */
    private String identifyLocation(GeTruckDrivingRecord record) {
        if (record == null || record.getLat() == null || record.getLon() == null) {
            return "UNKNOWN";
        }
        
        double lat = record.getLat().doubleValue();
        double lon = record.getLon().doubleValue();
        
        return LocationConstants.identifyLocation(lat, lon);
    }
    
    /**
     * 从位置序列推断路径类型
     */
    private RouteInfo.RouteType inferRouteFromLocationSequence(List<String> sequence) {
        if (sequence.size() < 2) {
            return RouteInfo.RouteType.START_TO_LOADING; // 默认从出发点到装货点
        }
        
        // 分析最近5分钟的位置变化来确定当前路径
        String recentLocation = sequence.get(sequence.size() - 1);
        String previousLocation = sequence.get(sequence.size() - 2);
        
        // 基于最近的位置变化推断路径
        if ("LOADING".equals(previousLocation) && ("IN_TRANSIT".equals(recentLocation) || "UNLOADING".equals(recentLocation))) {
            return RouteInfo.RouteType.LOADING_TO_UNLOADING;
        } else if ("UNLOADING".equals(previousLocation) && ("IN_TRANSIT".equals(recentLocation) || "CHARGING".equals(recentLocation))) {
            return RouteInfo.RouteType.UNLOADING_TO_CHARGING;
        } else if ("CHARGING".equals(previousLocation) && ("IN_TRANSIT".equals(recentLocation) || "LOADING".equals(recentLocation))) {
            return RouteInfo.RouteType.CHARGING_TO_LOADING;
        } else if ("UNLOADING".equals(previousLocation) && ("IN_TRANSIT".equals(recentLocation) || "LOADING".equals(recentLocation))) {
            // 检查是否是返程（卸货点直接返回装货点）
            return RouteInfo.RouteType.UNLOADING_TO_LOADING;
        }
        
        // 如果当前在运输途中，使用更复杂的分析
        if ("IN_TRANSIT".equals(recentLocation)) {
            return inferRouteFromDirection(sequence);
        }
        
        return RouteInfo.RouteType.START_TO_LOADING; // 默认
    }
    
    /**
     * 基于方向推断路径（当前在途中时）
     */
    private RouteInfo.RouteType inferRouteFromDirection(List<String> sequence) {
        // 查找最近的明确位置，向前查找最多3个位置
        for (int i = sequence.size() - 2; i >= Math.max(0, sequence.size() - 4); i--) {
            String location = sequence.get(i);
            if (!"IN_TRANSIT".equals(location)) {
                if ("LOADING".equals(location)) {
                    return RouteInfo.RouteType.LOADING_TO_UNLOADING;
                } else if ("UNLOADING".equals(location)) {
                    // 需要判断是去换电站还是返回装货点
                    // 如果前面有换电站记录，则可能是返程
                    if (sequence.contains("CHARGING")) {
                        return RouteInfo.RouteType.UNLOADING_TO_LOADING;
                    } else {
                        return RouteInfo.RouteType.UNLOADING_TO_CHARGING;
                    }
                } else if ("CHARGING".equals(location)) {
                    return RouteInfo.RouteType.CHARGING_TO_LOADING;
                }
            }
        }
        
        return RouteInfo.RouteType.START_TO_LOADING;
    }
    
    /**
     * 使用方向向量验证路径
     */
    private RouteInfo.RouteType validateRouteWithDirectionVector(RouteInfo.RouteType preliminaryRoute,
                                                               GeTruckDrivingRecord currentStatus,
                                                               List<GeTruckDrivingRecord> historyTrack) {
        
        // 计算最近几个点的移动方向
        GeTruckDrivingRecord recentRecord = historyTrack.get(historyTrack.size() - 1);
        double bearing = calculateBearing(
            recentRecord.getLat().doubleValue(), recentRecord.getLon().doubleValue(),
            currentStatus.getLat().doubleValue(), currentStatus.getLon().doubleValue()
        );
        
        // 计算各目标点的方位角
        double bearingToUnloading = calculateBearing(
            currentStatus.getLat().doubleValue(), currentStatus.getLon().doubleValue(),
            LocationConstants.UNLOADING_POINT.getLatitude(), LocationConstants.UNLOADING_POINT.getLongitude()
        );
        double bearingToCharging = calculateBearing(
            currentStatus.getLat().doubleValue(), currentStatus.getLon().doubleValue(),
            LocationConstants.CHARGING_STATION.getLatitude(), LocationConstants.CHARGING_STATION.getLongitude()
        );
        double bearingToLoading = calculateBearing(
            currentStatus.getLat().doubleValue(), currentStatus.getLon().doubleValue(),
            LocationConstants.LOADING_POINT.getLatitude(), LocationConstants.LOADING_POINT.getLongitude()
        );
        
        // 找到方向最匹配的路径
        double diffToUnloading = Math.abs(normalizeAngle(bearing - bearingToUnloading));
        double diffToCharging = Math.abs(normalizeAngle(bearing - bearingToCharging));
        double diffToLoading = Math.abs(normalizeAngle(bearing - bearingToLoading));
        
        double minDiff = Math.min(Math.min(diffToUnloading, diffToCharging), diffToLoading);
        
        // 如果方向差异小于45度，认为匹配
        if (minDiff < 45.0) {
            if (minDiff == diffToUnloading) {
                return RouteInfo.RouteType.LOADING_TO_UNLOADING;
            } else if (minDiff == diffToCharging) {
                return RouteInfo.RouteType.UNLOADING_TO_CHARGING;
            } else {
                // 可能是返回装货点，需要进一步判断
                if (preliminaryRoute == RouteInfo.RouteType.UNLOADING_TO_LOADING) {
                    return RouteInfo.RouteType.UNLOADING_TO_LOADING;
                } else {
                    return RouteInfo.RouteType.CHARGING_TO_LOADING;
                }
            }
        }
        
        return preliminaryRoute; // 保持原判断结果
    }
    
    /**
     * 计算方位角
     */
    private double calculateBearing(double lat1, double lon1, double lat2, double lon2) {
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLonRad = Math.toRadians(lon2 - lon1);
        
        double y = Math.sin(deltaLonRad) * Math.cos(lat2Rad);
        double x = Math.cos(lat1Rad) * Math.sin(lat2Rad) -
                Math.sin(lat1Rad) * Math.cos(lat2Rad) * Math.cos(deltaLonRad);
        
        double bearingRad = Math.atan2(y, x);
        double bearingDeg = Math.toDegrees(bearingRad);
        
        // 转换为0-360度
        return (bearingDeg + 360) % 360;
    }
    
    /**
     * 标准化角度到 -180 到 180 度范围
     */
    private double normalizeAngle(double angle) {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
    
    /**
     * 设置路径的起始和目标位置
     */
    public void setRouteStartAndTarget(RouteInfo routeInfo, RouteInfo.RouteType routeType) {
        switch (routeType) {
            case START_TO_LOADING:
                routeInfo.setStartLocation("START");
                routeInfo.setTargetLocation("LOADING");
                routeInfo.setTotalDistanceKm(LocationConstants.START_TO_LOADING_DISTANCE_KM);
                break;
            case LOADING_TO_UNLOADING:
                routeInfo.setStartLocation("LOADING");
                routeInfo.setTargetLocation("UNLOADING");
                routeInfo.setTotalDistanceKm(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM);
                break;
            case UNLOADING_TO_CHARGING:
                routeInfo.setStartLocation("UNLOADING");
                routeInfo.setTargetLocation("CHARGING");
                routeInfo.setTotalDistanceKm(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM);
                break;
            case CHARGING_TO_LOADING:
                routeInfo.setStartLocation("CHARGING");
                routeInfo.setTargetLocation("LOADING");
                routeInfo.setTotalDistanceKm(LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM);
                break;
            case UNLOADING_TO_LOADING:
                routeInfo.setStartLocation("UNLOADING");
                routeInfo.setTargetLocation("LOADING");
                routeInfo.setTotalDistanceKm(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM + 
                                           LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM);
                break;
            default:
                // 默认出发点到装货点
                routeInfo.setStartLocation("START");
                routeInfo.setTargetLocation("LOADING");
                routeInfo.setTotalDistanceKm(LocationConstants.START_TO_LOADING_DISTANCE_KM);
                break;
        }
    }
    
    /**
     * 计算剩余距离和进度
     */
    private void calculateRemainingDistance(RouteInfo routeInfo, GeTruckDrivingRecord currentStatus) {
        double[] targetCoords = LocationConstants.getLocationCoordinates(routeInfo.getTargetLocation());
        double[] startCoords = LocationConstants.getLocationCoordinates(routeInfo.getStartLocation());
        if (currentStatus != null && targetCoords != null && startCoords != null) {
            double currentLat = currentStatus.getLat().doubleValue();
            double currentLon = currentStatus.getLon().doubleValue();


            double total = LocationConstants.calculateDistance(
                    startCoords[0], startCoords[1], targetCoords[0], targetCoords[1]
            );

            // 计算到目标点的直线距离（米）
            double remainingDistanceMeters = LocationConstants.calculateDistance(
                currentLat, currentLon, targetCoords[0], targetCoords[1]
            );
            if (remainingDistanceMeters >= total) {
                double traveledDistanceMeters = LocationConstants.calculateDistance(
                        startCoords[0], startCoords[1],currentLat, currentLon);
                remainingDistanceMeters = total - traveledDistanceMeters;
            }

            double remainingDistanceKm = remainingDistanceMeters / total * routeInfo.getTotalDistanceKm();

            routeInfo.setRemainingDistanceKm(remainingDistanceKm);
            
            // 计算已行驶距离
            routeInfo.setTraveledDistanceKm(routeInfo.getTotalDistanceKm() - routeInfo.getRemainingDistanceKm());
            
            // 计算剩余路程占比
            routeInfo.calculateRemainingPercentage();
        }
    }
    
    /**
     * 计算路径判断的置信度
     */
    private void calculateRouteConfidence(RouteInfo routeInfo) {
        double confidence = 0.5; // 基础置信度
        
        List<GeTruckDrivingRecord> historyTrack = routeInfo.getHistoryTrack();
        if (historyTrack != null) {
            // 轨迹点数量越多，置信度越高
            confidence += Math.min(historyTrack.size() / 40.0 * 0.3, 0.3);
            
            // 轨迹的连续性
            if (historyTrack.size() >= 2) {
                double continuity = calculateTrajectoryContinuity(historyTrack);
                confidence += continuity * 0.2;
            }
        }
        
        // 当前位置与路径的匹配度
        if (routeInfo.getCurrentRoute() != RouteInfo.RouteType.UNKNOWN) {
            confidence += 0.2;
        }
        
        // 限制在0-1范围内
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        routeInfo.setConfidence(confidence);
    }
    
    /**
     * 计算轨迹的连续性
     */
    private double calculateTrajectoryContinuity(List<GeTruckDrivingRecord> track) {
        if (track.size() < 2) return 0.0;
        
        double totalGaps = 0;
        int validGaps = 0;
        
        for (int i = 1; i < track.size(); i++) {
            long gap = ChronoUnit.SECONDS.between(
                track.get(i-1).getReportTime(), 
                track.get(i).getReportTime()
            );
            
            if (gap > 0 && gap < 300) { // 5分钟以内的间隔认为有效
                totalGaps += gap;
                validGaps++;
            }
        }
        
        if (validGaps == 0) return 0.0;
        
        double averageGap = totalGaps / validGaps;
        // 接近30秒间隔的轨迹连续性最好
        return Math.max(0.0, 1.0 - Math.abs(averageGap - 30) / 30.0);
    }
    
    /**
     * 创建默认路径信息（当分析失败时）
     */
    private RouteInfo createDefaultRouteInfo(String vehicleNo) {
        RouteInfo routeInfo = new RouteInfo();
        routeInfo.setVehicleNo(vehicleNo);
        routeInfo.setCurrentRoute(RouteInfo.RouteType.START_TO_LOADING);
        routeInfo.setStartLocation("START");
        routeInfo.setTargetLocation("LOADING");
        routeInfo.setTotalDistanceKm(LocationConstants.START_TO_LOADING_DISTANCE_KM);
        routeInfo.setConfidence(0.3); // 低置信度
        
        // 设置默认Truck信息
        Truck truck = getTruckInfo(vehicleNo);
        routeInfo.setTruck(truck);
        
        return routeInfo;
    }
    
    /**
     * TODO: 这里不应该是从redis获取，而是初始化时生成
     * 从Redis获取Truck信息
     * @param vehicleNo 车辆编号
     * @return Truck对象
     */
    private Truck getTruckInfo(String vehicleNo) {
        Truck truck = new Truck(vehicleNo, BigDecimal.valueOf(82), LocationConstants.DEFAULT_BATTERY_CAPACITY_DECIMAL_KWH);
        return truck;
        // try {
        //     String redisKey = DpConstants.DP_TRUCK_INFO_KEY + vehicleNo;
        //     RBucket<String> bucket = redissonClient.getBucket(redisKey);
        //     String jsonStr = bucket.get();
        //
        //     if (jsonStr == null || jsonStr.trim().isEmpty()) {
        //         log.warn("车辆 {} 在Redis中没有Truck信息，创建默认Truck对象", vehicleNo);
        //         return new Truck(vehicleNo, 80.0, 100.0); // 默认SOC和容量
        //     }
        //
        //     Truck truck = JSONUtil.toBean(jsonStr, Truck.class);
        //     log.debug("获取车辆 {} Truck信息：{}", vehicleNo, truck);
        //
        //     return truck;
        //
        // } catch (Exception e) {
        //     log.error("获取车辆 {} Truck信息失败，使用默认值", vehicleNo, e);
        //     return new Truck(vehicleNo, 80.0, 100.0); // 默认SOC和容量
        // }
    }

} 