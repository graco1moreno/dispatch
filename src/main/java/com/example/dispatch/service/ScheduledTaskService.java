package com.example.dispatch.service;

import com.example.dispatch.DispatchSimulation;
import com.example.dispatch.model.RouteInfo;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 定时任务服务
 */
@Slf4j
@Service
public class ScheduledTaskService {
    
    @Autowired
    private RedissonClient redissonClient;
    
    @Autowired
    private VehicleTrackingService vehicleTrackingService;
    
    @Autowired
    private SOCCalculationService socCalculationService;
    
    @Autowired
    private TestDataService testDataService;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 定时执行调度模拟任务
     * 每10分钟执行一次
     */
    @Scheduled(fixedRate = 2 * 60 * 1000) // 5分钟执行一次
    public void executeDispatchSimulation() {
        String currentTime = LocalDateTime.now().format(formatter);
        log.info("=== 开始执行调度模拟任务，执行时间：{} ===", currentTime);
        
        try {
            // 创建调度模拟实例
            DispatchSimulation simulation = new DispatchSimulation(vehicleTrackingService, socCalculationService, testDataService);
            
            // 开始模拟
            simulation.startSimulation();
            
            // 输出换电记录
            String jsonResult = simulation.getExchangeRecordsAsJson();
            log.info("换电记录JSON：\n{}", jsonResult);
            
            // 输出调度记录
            String scheduleRecordsJson = simulation.getScheduleRecordsAsJson();
            log.info("调度记录JSON：\n{}", scheduleRecordsJson);
            
            log.info("=== 调度模拟任务执行完成，执行时间：{} ===", currentTime);
            
        } catch (Exception e) {
            log.error("调度模拟任务执行异常，执行时间：{}", currentTime, e);
        }
    }
    
    /**
     * 定时分析车辆状态和SOC消耗
     * 每5分钟执行一次
     */
    // @Scheduled(fixedRate = 2 * 60 * 1000) // 5分钟执行一次
    public void analyzeVehicleStatusAndSOC() {
        String currentTime = LocalDateTime.now().format(formatter);
        log.info("=== 开始执行车辆状态和SOC分析任务，执行时间：{} ===", currentTime);
        
        try {
            // 1. 生成测试数据

            // testDataService.generateTestVehicleData();
            // 2. 分析测试车辆状态
            String[] testVehicles = {"粤G03335D"};
            
            for (String vehicleNo : testVehicles) {
                analyzeVehicleComprehensive(vehicleNo);
            }
            
            // 3. 获取测试数据概览
            Map<String, Object> overview = testDataService.getTestDataOverview();
            log.info("测试数据概览：{}", overview);
            
            log.info("=== 车辆状态和SOC分析任务执行完成，执行时间：{} ===", currentTime);
            
        } catch (Exception e) {
            log.error("车辆状态和SOC分析任务执行异常，执行时间：{}", currentTime, e);
        }
    }
    
    /**
     * 综合分析单辆车的状态
     */
    private void analyzeVehicleComprehensive(String vehicleNo) {
        try {
            log.info("--- 开始分析车辆：{} ---", vehicleNo);
            
            // 1. 分析车辆路径状态
            RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo, null);
            log.info("车辆路径分析结果：{}", routeInfo);
            
            // 2. 计算完整运输SOC消耗
            double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC(routeInfo);
            log.info("一趟完整运输SOC消耗：{}%", completeTransportSOC);
            
            // 3. 计算本趟剩余行驶公里数对应的SOC消耗
            double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);
            log.info("本趟剩余行驶SOC消耗：{}%", remainingTripSOC);
            
            // 4. 判断是否需要换电
            BigDecimal currentSOC = routeInfo.getCurrentSoc();
            boolean needCharging = socCalculationService.shouldGoToChargingStation(currentSOC, completeTransportSOC, remainingTripSOC);
            log.info("换电判断结果：当前SOC={}%, 需要换电={}", currentSOC != null ? currentSOC.doubleValue() : "未知", needCharging);
            
            // 5. 生成详细分析报告
            Map<String, Object> detailedAnalysis = socCalculationService.getDetailedSOCAnalysis(routeInfo);
            log.info("详细SOC分析报告：{}", detailedAnalysis);
            
            // 6. 换电建议
            if (needCharging) {
                log.warn("车辆 {} 建议前往换电站换电！", vehicleNo);
            } else {
                log.info("车辆 {} 电量充足，可继续运营", vehicleNo);
            }
            
            log.info("--- 车辆 {} 分析完成 ---", vehicleNo);
            
        } catch (Exception e) {
            log.error("分析车辆 {} 状态失败", vehicleNo, e);
        }
    }
    

} 