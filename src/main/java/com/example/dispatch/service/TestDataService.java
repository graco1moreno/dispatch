package com.example.dispatch.service;

import cn.hutool.json.JSONUtil;
import com.example.dispatch.constant.DpConstants;
import com.example.dispatch.constant.LocationConstants;
import com.example.dispatch.model.GeTruckDrivingRecord;
import com.example.dispatch.model.Truck;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 测试数据服务
 * 用于生成和管理测试用的车辆状态数据
 */
@Slf4j
@Service
public class TestDataService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    /**
     * 生成测试车辆状态数据并存储到Redis
     */
    public void generateTestVehicleData() {
        log.info("开始生成测试车辆状态数据");
        
        // 车辆1：在装货点附近
        GeTruckDrivingRecord vehicle1 = createTestRecord(
            "粤G02286D", 
            new BigDecimal("110.163891"),
            new BigDecimal("21.425126"),
            new BigDecimal("82"),
            new BigDecimal("45.2")
        );
        
        // 生成对应的Truck对象
        Truck truck1 = new Truck("粤G02286D", 82, LocationConstants.DEFAULT_BATTERY_CAPACITY_KWH); // SOC=82%, 容量=282kWh
        
        // 车辆2：在运输途中（装货点到卸货点）
        double midLat = (LocationConstants.LOADING_POINT.getLatitude() + LocationConstants.UNLOADING_POINT.getLatitude()) / 2;
        double midLon = (LocationConstants.LOADING_POINT.getLongitude() + LocationConstants.UNLOADING_POINT.getLongitude()) / 2;
        GeTruckDrivingRecord vehicle2 = createTestRecord(
            "粤G03335D",
            new BigDecimal(String.valueOf(midLon)),
            new BigDecimal(String.valueOf(midLat)),
            new BigDecimal("68.3"),
            new BigDecimal("52.1")
        );
        
        // 生成对应的Truck对象
        Truck truck2 = new Truck("粤G03335D", 68.3, 300); // SOC=68.3%, 容量=300kWh
        
        // 存储到Redis
        storeVehicleStatus(vehicle1);
        storeTruckInfo(truck1);
        storeVehicleStatus(vehicle2);
        storeTruckInfo(truck2);
        
        // 生成历史轨迹数据
        generateHistoryTrackData("粤G02286D");
        generateHistoryTrackData("粤G03335D");
        
        log.info("测试车辆状态数据生成完成");
    }
    
    /**
     * 生成历史轨迹数据
     */
    private void generateHistoryTrackData(String vehicleNo) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_RECORD + vehicleNo;
            RList<String> redisList = redissonClient.getList(redisKey);
            
            // 清除旧数据
            redisList.clear();
            
            // 生成30分钟内的轨迹数据（每30秒一个点）
            LocalDateTime startTime = LocalDateTime.now().minusMinutes(30);
            
            // 初始累计数据
            double baseKm = 12000.0 + Math.random() * 1000; // 基础累计里程
            double basePowerConsumption = 8000.0 + Math.random() * 500; // 基础累计电耗
            
            for (int i = 0; i < 60; i++) { // 30分钟 / 30秒 = 60个点
                LocalDateTime pointTime = startTime.plusSeconds(i * 30);
                
                // 模拟从装货点到卸货点的移动轨迹
                double progress = (double) i / 59; // 0 到 1 的进度
                double lat = LocationConstants.LOADING_POINT.getLatitude() + 
                           (LocationConstants.UNLOADING_POINT.getLatitude() - LocationConstants.LOADING_POINT.getLatitude()) * progress;
                double lon = LocationConstants.LOADING_POINT.getLongitude() + 
                           (LocationConstants.UNLOADING_POINT.getLongitude() - LocationConstants.LOADING_POINT.getLongitude()) * progress;
                
                // 累计里程和电耗数据（随时间递增）
                double currentKm = baseKm + (progress * 25.0); // 模拟行驶25公里
                double currentPowerConsumption = basePowerConsumption + (progress * 35.0); // 模拟消耗35kWh
                
                GeTruckDrivingRecord trackPoint = createTestRecord(
                    vehicleNo,
                    new BigDecimal(String.valueOf(lon)),
                    new BigDecimal(String.valueOf(lat)),
                    new BigDecimal(String.valueOf(85.0 - i * 0.25)), // SOC逐渐下降
                    new BigDecimal(String.valueOf(40.0 + Math.random() * 20)) // 速度40-60之间随机
                );
                trackPoint.setReportTime(pointTime);
                trackPoint.setTotalDrivingKm(new BigDecimal(String.valueOf(currentKm)));
                trackPoint.setTotalPowerConsumption(new BigDecimal(String.valueOf(currentPowerConsumption)));
                trackPoint.setAveragePowerConsumption(new BigDecimal("140.0")); // 平均百公里电耗
                
                String jsonStr = JSONUtil.toJsonStr(trackPoint);
                redisList.add(jsonStr);
            }
            
            // 设置过期时间为1小时
            redisList.expire(1, TimeUnit.HOURS);
            
            log.info("车辆 {} 历史轨迹数据已生成，共 {} 个点", vehicleNo, redisList.size());
            
        } catch (Exception e) {
            log.error("生成车辆 {} 历史轨迹数据失败", vehicleNo, e);
        }
    }
    
    /**
     * 创建测试车辆记录
     * @param truckNo 车辆编号
     * @param lon 经度
     * @param lat 纬度
     */
    private GeTruckDrivingRecord createTestRecord(String truckNo, BigDecimal lon, BigDecimal lat,
                                                 BigDecimal soc, BigDecimal speed) {
        GeTruckDrivingRecord record = new GeTruckDrivingRecord();
        record.setTruckNo(truckNo);
        record.setLat(lat);
        record.setLon(lon);
        record.setSoc(soc);
        record.setSpeed(speed);
        record.setReportTime(LocalDateTime.now());
        record.setEngineStatus(1); // 启动状态
        record.setTotalDrivingKm(new BigDecimal("12345.67"));
        record.setTotalPowerConsumption(new BigDecimal("8765.43"));
        record.setAveragePowerConsumption(new BigDecimal("71.0"));
        record.setSource("TEST_DATA");
        record.setCopName("测试车队");
        record.setCreateTime(LocalDateTime.now());
        
        return record;
    }
    
    /**
     * 存储车辆状态到Redis
     */
    private void storeVehicleStatus(GeTruckDrivingRecord record) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_STATUS_KEY + record.getTruckNo();
            String jsonStr = JSONUtil.toJsonStr(record);
            
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            bucket.set(jsonStr);
            
            // 设置过期时间为1小时
            bucket.expire(1, TimeUnit.HOURS);
            
            log.info("车辆 {} 状态数据已存储到Redis：{}", record.getTruckNo(), jsonStr);
            
        } catch (Exception e) {
            log.error("存储车辆 {} 状态数据失败", record.getTruckNo(), e);
        }
    }
    
    /**
     * 存储Truck信息到Redis
     */
    private void storeTruckInfo(Truck truck) {
        try {
            String redisKey = DpConstants.DP_TRUCK_INFO_KEY + truck.getTruckNo();
            String jsonStr = JSONUtil.toJsonStr(truck);
            
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            bucket.set(jsonStr);
            
            // 设置过期时间为1小时
            bucket.expire(1, TimeUnit.HOURS);
            
            log.info("车辆 {} 信息已存储到Redis：{}", truck.getTruckNo(), jsonStr);
            
        } catch (Exception e) {
            log.error("存储车辆 {} 信息失败", truck.getTruckNo(), e);
        }
    }
    
    /**
     * 更新车辆位置（模拟车辆移动）
     */
    public void updateVehicleLocation(String truckNo, BigDecimal lat, BigDecimal lon, BigDecimal soc) {
        try {
            String redisKey = DpConstants.DP_TRUCK_DRIVING_STATUS_KEY + truckNo;
            RBucket<String> bucket = redissonClient.getBucket(redisKey);
            String jsonStr = bucket.get();
            
            if (jsonStr != null) {
                GeTruckDrivingRecord record = JSONUtil.toBean(jsonStr, GeTruckDrivingRecord.class);
                record.setLat(lat);
                record.setLon(lon);
                record.setSoc(soc);
                record.setReportTime(LocalDateTime.now());
                
                bucket.set(JSONUtil.toJsonStr(record));
                log.info("车辆 {} 位置已更新：({}, {})，SOC={}%", truckNo, lat, lon, soc);
            }
            
        } catch (Exception e) {
            log.error("更新车辆 {} 位置失败", truckNo, e);
        }
    }
    
    /**
     * 获取Redis中的测试数据概览
     */
    public Map<String, Object> getTestDataOverview() {
        Map<String, Object> overview = new HashMap<>();
        
        String[] testVehicles = {"粤G02286D", "粤G03335D"};
        
        for (String vehicleNo : testVehicles) {
            try {
                String redisKey = DpConstants.DP_TRUCK_DRIVING_STATUS_KEY + vehicleNo;
                RBucket<String> bucket = redissonClient.getBucket(redisKey);
                String jsonStr = bucket.get();
                
                if (jsonStr != null) {
                    GeTruckDrivingRecord record = JSONUtil.toBean(jsonStr, GeTruckDrivingRecord.class);
                    Map<String, Object> vehicleData = new HashMap<>();
                    vehicleData.put("truckNo", record.getTruckNo());
                    vehicleData.put("location", String.format("%.4f, %.4f", 
                            record.getLat().doubleValue(), record.getLon().doubleValue()));
                    vehicleData.put("soc", record.getSoc() + "%");
                    vehicleData.put("speed", record.getSpeed() + "km/h");
                    vehicleData.put("reportTime", record.getReportTime());
                    
                    // 获取历史轨迹数量
                    String historyKey = DpConstants.DP_TRUCK_DRIVING_RECORD + vehicleNo;
                    RList<String> historyList = redissonClient.getList(historyKey);
                    vehicleData.put("historyTrackCount", historyList.size());
                    
                    overview.put(vehicleNo, vehicleData);
                } else {
                    overview.put(vehicleNo, "无数据");
                }
                
            } catch (Exception e) {
                overview.put(vehicleNo, "数据获取失败：" + e.getMessage());
            }
        }
        
        return overview;
    }
    
    /**
     * 模拟车辆移动到指定位置
     */
    public void simulateVehicleMovement(String vehicleNo, String targetLocation) {
        try {
            double[] targetCoords = LocationConstants.getLocationCoordinates(targetLocation);
            if (targetCoords == null) {
                log.warn("无效的目标位置：{}", targetLocation);
                return;
            }
            
            // 模拟SOC变化
            BigDecimal newSOC = new BigDecimal("65.0"); // 模拟消耗后的SOC
            
            updateVehicleLocation(vehicleNo, 
                new BigDecimal(String.valueOf(targetCoords[0])), 
                new BigDecimal(String.valueOf(targetCoords[1])), 
                newSOC);
            
            log.info("模拟车辆 {} 移动到 {} 完成", vehicleNo, targetLocation);
            
        } catch (Exception e) {
            log.error("模拟车辆 {} 移动失败", vehicleNo, e);
        }
    }
} 