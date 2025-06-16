// package com.example.dispatch.controller;
//
// import com.example.dispatch.model.RouteInfo;
// import com.example.dispatch.service.SOCCalculationService;
// import com.example.dispatch.service.TestDataService;
// import com.example.dispatch.service.VehicleTrackingService;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.beans.factory.annotation.Autowired;
// import org.springframework.web.bind.annotation.*;
//
// import java.math.BigDecimal;
// import java.util.HashMap;
// import java.util.Map;
//
// /**
//  * 智能调度控制器
//  * 提供车辆路径分析、SOC计算和换电判断的Web API
//  */
// @Slf4j
// @RestController
// @RequestMapping("/api/smart-dispatch")
// public class SmartDispatchController {
//
//     @Autowired
//     private VehicleTrackingService vehicleTrackingService;
//
//     @Autowired
//     private SOCCalculationService socCalculationService;
//
//     @Autowired
//     private TestDataService testDataService;
//
//     /**
//      * 分析车辆路径状态
//      * GET /api/smart-dispatch/route/{vehicleNo}
//      */
//     @GetMapping("/route/{vehicleNo}")
//     public Map<String, Object> analyzeVehicleRoute(@PathVariable String vehicleNo) {
//         log.info("API请求：分析车辆 {} 的路径状态", vehicleNo);
//
//         try {
//             RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo);
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("vehicleNo", vehicleNo);
//             response.put("routeInfo", routeInfo);
//             response.put("message", "路径分析成功");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("分析车辆 {} 路径失败", vehicleNo, e);
//             return createErrorResponse("路径分析失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 计算完整运输SOC消耗
//      * GET /api/smart-dispatch/soc/complete-transport
//      */
//     @GetMapping("/soc/complete-transport")
//     public Map<String, Object> calculateCompleteTransportSOC() {
//         log.info("API请求：计算完整运输SOC消耗");
//
//         try {
//             double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC();
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("completeTransportSOC", completeTransportSOC);
//             response.put("description", "装货点→卸货点→换电站的SOC消耗");
//             response.put("unit", "%");
//             response.put("message", "计算成功");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("计算完整运输SOC消耗失败", e);
//             return createErrorResponse("计算失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 计算车辆剩余行驶SOC消耗
//      * GET /api/smart-dispatch/soc/remaining/{vehicleNo}
//      */
//     @GetMapping("/soc/remaining/{vehicleNo}")
//     public Map<String, Object> calculateRemainingTripSOC(@PathVariable String vehicleNo) {
//         log.info("API请求：计算车辆 {} 剩余行驶SOC消耗", vehicleNo);
//
//         try {
//             RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo);
//             double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("vehicleNo", vehicleNo);
//             response.put("remainingTripSOC", remainingTripSOC);
//             response.put("routeType", routeInfo.getCurrentRoute().getDescription());
//             response.put("remainingDistanceKm", routeInfo.getRemainingDistanceKm());
//             response.put("remainingPercentage", routeInfo.getRemainingPercentage() * 100);
//             response.put("unit", "%");
//             response.put("message", "计算成功");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("计算车辆 {} 剩余行驶SOC消耗失败", vehicleNo, e);
//             return createErrorResponse("计算失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 判断车辆是否需要换电
//      * GET /api/smart-dispatch/charging/check/{vehicleNo}
//      */
//     @GetMapping("/charging/check/{vehicleNo}")
//     public Map<String, Object> checkChargingNeed(@PathVariable String vehicleNo) {
//         log.info("API请求：检查车辆 {} 是否需要换电", vehicleNo);
//
//         try {
//             // 1. 分析车辆路径
//             RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo);
//
//             // 2. 计算各种SOC消耗
//             double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC(routeInfo);
//
//             double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);
//
//             // 3. 换电判断
//             boolean needCharging = socCalculationService.shouldGoToChargingStation(
//                 routeInfo.getCurrentSoc(), completeTransportSOC, remainingTripSOC);
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("vehicleNo", vehicleNo);
//             response.put("needCharging", needCharging);
//             response.put("currentSOC", routeInfo.getCurrentSoc() != null ?
//                     routeInfo.getCurrentSoc().doubleValue() : null);
//             response.put("completeTransportSOC", completeTransportSOC);
//             response.put("remainingTripSOC", remainingTripSOC);
//             response.put("safetyMargin", 10.0);
//             response.put("totalRequiredSOC", completeTransportSOC + remainingTripSOC + 10.0);
//             response.put("recommendation", needCharging ? "建议立即前往换电站换电" : "电量充足，可继续运营");
//             response.put("message", "检查完成");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("检查车辆 {} 换电需求失败", vehicleNo, e);
//             return createErrorResponse("检查失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 获取车辆详细SOC分析报告
//      * GET /api/smart-dispatch/analysis/{vehicleNo}
//      */
//     @GetMapping("/analysis/{vehicleNo}")
//     public Map<String, Object> getDetailedAnalysis(@PathVariable String vehicleNo) {
//         log.info("API请求：获取车辆 {} 详细SOC分析报告", vehicleNo);
//
//         try {
//             RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo);
//             Map<String, Object> analysis = socCalculationService.getDetailedSOCAnalysis(routeInfo);
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("vehicleNo", vehicleNo);
//             response.put("analysis", analysis);
//             response.put("message", "分析完成");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("获取车辆 {} 详细分析失败", vehicleNo, e);
//             return createErrorResponse("分析失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 生成测试数据
//      * POST /api/smart-dispatch/test/generate
//      */
//     @PostMapping("/test/generate")
//     public Map<String, Object> generateTestData() {
//         log.info("API请求：生成测试数据");
//
//         try {
//             testDataService.generateTestVehicleData();
//             Map<String, Object> overview = testDataService.getTestDataOverview();
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("message", "测试数据生成成功");
//             response.put("testDataOverview", overview);
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("生成测试数据失败", e);
//             return createErrorResponse("生成失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 获取测试数据概览
//      * GET /api/smart-dispatch/test/overview
//      */
//     @GetMapping("/test/overview")
//     public Map<String, Object> getTestDataOverview() {
//         log.info("API请求：获取测试数据概览");
//
//         try {
//             Map<String, Object> overview = testDataService.getTestDataOverview();
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("overview", overview);
//             response.put("message", "获取成功");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("获取测试数据概览失败", e);
//             return createErrorResponse("获取失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 模拟车辆移动
//      * POST /api/smart-dispatch/test/move/{vehicleNo}
//      */
//     @PostMapping("/test/move/{vehicleNo}")
//     public Map<String, Object> simulateVehicleMovement(
//             @PathVariable String vehicleNo,
//             @RequestParam String targetLocation) {
//         log.info("API请求：模拟车辆 {} 移动到 {}", vehicleNo, targetLocation);
//
//         try {
//             testDataService.simulateVehicleMovement(vehicleNo, targetLocation);
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("vehicleNo", vehicleNo);
//             response.put("targetLocation", targetLocation);
//             response.put("message", "模拟移动成功");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("模拟车辆 {} 移动失败", vehicleNo, e);
//             return createErrorResponse("模拟移动失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 综合分析多辆车
//      * GET /api/smart-dispatch/fleet/analysis
//      */
//     @GetMapping("/fleet/analysis")
//     public Map<String, Object> analyzeFleet() {
//         log.info("API请求：车队综合分析");
//
//         try {
//             String[] testVehicles = {"粤G02286D", "粤G03335D"};
//             Map<String, Object> fleetAnalysis = new HashMap<>();
//
//             for (String vehicleNo : testVehicles) {
//                 try {
//                     RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(vehicleNo);
//                     Map<String, Object> vehicleAnalysis = socCalculationService.getDetailedSOCAnalysis(routeInfo);
//                     fleetAnalysis.put(vehicleNo, vehicleAnalysis);
//                 } catch (Exception e) {
//                     fleetAnalysis.put(vehicleNo, "分析失败: " + e.getMessage());
//                 }
//             }
//
//             Map<String, Object> response = new HashMap<>();
//             response.put("success", true);
//             response.put("fleetAnalysis", fleetAnalysis);
//             response.put("analyzedVehicles", testVehicles.length);
//             response.put("message", "车队分析完成");
//
//             return response;
//
//         } catch (Exception e) {
//             log.error("车队分析失败", e);
//             return createErrorResponse("车队分析失败: " + e.getMessage());
//         }
//     }
//
//     /**
//      * 创建错误响应
//      */
//     private Map<String, Object> createErrorResponse(String message) {
//         Map<String, Object> response = new HashMap<>();
//         response.put("success", false);
//         response.put("message", message);
//         response.put("timestamp", System.currentTimeMillis());
//         return response;
//     }
// }