# RouteInfo增加Truck对象并修正SOC计算

## 任务背景
RouteInfo对象需要增加Truck对象，calculateRouteSOC方法当前返回的是电量而不是SOC，需要根据Truck.capacity计算真正的SOC百分比。

## 执行计划
1. 修改RouteInfo类，添加Truck字段
2. 修改SOCCalculationService的calculateRouteSOC方法，返回真正的SOC百分比
3. 更新所有调用点，传递truck.capacity参数
4. 验证代码逻辑一致性

## 补充需求（执行完成后的进一步要求）
1. 在generateTestVehicleData方法同时生成Truck对象存储到redis
2. 在analyzeVehicleRoute方法里根据vehicleNo从redis查到对应的truck对象并设置到RouteInfo里
3. calculateCompleteTransportSOC方法传入RouteInfo对象，并传到入calculateRouteSOC方法，在calculateRouteSOC里：adjustedConsumptionSOC / RouteInfo.truck.capacity，然后返回

## 技术细节
- SOC计算公式：最终采用 (adjustedConsumptionKwh / RouteInfo.truck.capacity) * 100
- Truck类已有capacity字段（BigDecimal类型，单位kWh）
- RouteInfo新增truck字段
- Redis存储：dispatch:truckInfo:{vehicleNo}

## 执行状态
- [x] 步骤1：修改RouteInfo类 - 已完成，添加了Truck truck字段
- [x] 步骤2：修改SOCCalculationService类 - 已完成，修正了calculateRouteSOC方法及相关方法
- [x] 步骤3：更新相关调用点 - 已完成，所有方法都能正确使用truck capacity
- [x] 步骤4：验证和测试 - 已完成，Maven编译通过

## 补充修改状态
- [x] 补充1：修改generateTestVehicleData方法 - 已完成，生成Truck对象存储到Redis
- [x] 补充2：修改analyzeVehicleRoute方法 - 已完成，从Redis获取Truck对象设置到RouteInfo
- [x] 补充3：修改calculateRouteSOC方法 - 已完成，使用adjustedConsumptionKwh / capacity * 100的计算方式

## 修改详情
1. **RouteInfo.java**：
   - 添加`private Truck truck;`字段

2. **SOCCalculationService.java**：
   - 修改`calculateRouteSOC`方法，接收RouteInfo参数，使用真实电量消耗除以容量
   - 增加`calculateCompleteTransportSOC(RouteInfo)`重载方法
   - 增加`getTruckCapacity(RouteInfo)`辅助方法
   - 更新所有路径计算方法使用RouteInfo
   - 在详细分析报告中添加车辆信息

3. **TestDataService.java**：
   - 在`generateTestVehicleData`方法中添加Truck对象生成和存储
   - 新增`storeTruckInfo`方法存储Truck信息到Redis

4. **VehicleTrackingService.java**：
   - 在`analyzeVehicleRoute`方法中添加从Redis获取Truck信息的逻辑
   - 新增`getTruckInfo`方法从Redis获取Truck对象
   - 修改`createDefaultRouteInfo`方法也设置Truck信息

5. **DpConstants.java**：
   - 添加`DP_TRUCK_INFO_KEY`常量用于Redis存储

## 验证结果
- Maven编译成功，无语法错误
- 完整的数据流：TestDataService生成Truck→Redis存储→VehicleTrackingService获取→SOCCalculationService计算
- SOC计算现在使用真实的电量消耗值除以车辆电池容量，返回准确的SOC百分比 