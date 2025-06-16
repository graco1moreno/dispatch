package com.example.dispatch;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.example.dispatch.constant.LocationConstants;
import com.example.dispatch.model.ExchangeRecord;
import com.example.dispatch.model.GeDispatchScheduleRecord;
import com.example.dispatch.model.RouteInfo;
import com.example.dispatch.model.Truck;
import com.example.dispatch.service.ExchangeStationService;
import com.example.dispatch.service.SOCCalculationService;
import com.example.dispatch.service.TestDataService;
import com.example.dispatch.service.TransportService;
import com.example.dispatch.service.VehicleTrackingService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;

/**
 * 调度模拟类
 */
@Slf4j
@Component
public class DispatchSimulation {
    private final List<Truck> trucks;
    private final ExchangeStationService exchangeStationService;
    private final TransportService transportService;

    private final VehicleTrackingService vehicleTrackingService;

    private final SOCCalculationService socCalculationService;

    private final TestDataService testDataService;
    private LocalDateTime initialTime;  // 初始时间
    private int remainingCargo;  // 剩余货物(吨)
    private Map<String, LocalDateTime> truckCompletionTimes; // 记录每辆车完成运输的时间
    private Map<String, Integer> truckDepartureOffsets; // 记录每辆车出发时间的偏移量
    private Map<String, List<Integer>> truckDepartureDelays; // 记录每辆车出发时间与参考时间的间隔
    private Map<String, RouteInfo> truckRouteMap;
    private Set<String> truckCurRouteSet; // 记录每辆车完成运输的时间

    // 添加调度记录管理
    private final List<GeDispatchScheduleRecord> scheduleRecords; // 调度记录列表
    private final Map<String, GeDispatchScheduleRecord> currentTripRecords; // 当前运输记录映射

    private static final int BATTERY_NUM = 5;  // 电池数量

    /**
     * 构造函数
     */
    public DispatchSimulation(VehicleTrackingService vehicleTrackingService, SOCCalculationService sOCCalculationService, TestDataService testDataService) {
        this.vehicleTrackingService = vehicleTrackingService;
        this.socCalculationService = sOCCalculationService;
        this.testDataService = testDataService;

        this.trucks = new ArrayList<>();
        this.exchangeStationService = new ExchangeStationService(BATTERY_NUM);
        this.transportService = new TransportService(exchangeStationService);
        this.initialTime = LocalDateTime.of(LocalDateTime.now().getYear(), LocalDateTime.now().getMonth(), LocalDateTime.now().getDayOfMonth(), 8, 0);  // 初始时间设置为8:00
        this.remainingCargo = TransportService.getTotalCargo();
        this.truckCompletionTimes = new ConcurrentHashMap<>();
        this.truckDepartureOffsets = new ConcurrentHashMap<>();
        this.truckDepartureDelays = new ConcurrentHashMap<>();
        this.truckRouteMap = new ConcurrentHashMap<>();
        this.truckCurRouteSet = new HashSet<>();

        // 初始化车辆
        initializeTrucks();

        // 初始化调度记录管理
        this.scheduleRecords = new ArrayList<>();
        this.currentTripRecords = new ConcurrentHashMap<>();
    }

    /**
     * 初始化车辆
     */
    private void initializeTrucks() {
        // 车辆清单
        String[] truckNos = {"粤G03335D"
                // "粤G05006D", "粤G08108D", "粤G08007D",
                // "粤G05595D", "粤G07117D", "粤G08232D",
                // "粤G02082D", "粤G02003D"
        };

        // TODO 这里车辆信息(车辆实时状态)
        for (String truckNo : truckNos) {
            Truck truck = new Truck(truckNo, BigDecimal.valueOf(82), LocationConstants.DEFAULT_BATTERY_CAPACITY_DECIMAL_KWH);
            trucks.add(truck);

            // 为每辆车初始化出发延迟列表
            truckDepartureDelays.put(truckNo, new ArrayList<>());
        }
    }

    /**
     * 准备模拟数据
     */
    private void prepareSimulationData() {
        try {
            // 1. 生成测试数据
            testDataService.generateTestVehicleData();

            // 2. 为每辆车生成个性化轨迹
            for (Truck truck : trucks) {
                testDataService.simulateVehicleMovement(truck.getTruckNo(), "LOADING_POINT");
            }

            // 3. 获取数据概览验证
            Map<String, Object> overview = testDataService.getTestDataOverview();
            System.out.println("模拟数据概览：" + overview);

        } catch (Exception e) {
            System.err.println("准备模拟数据失败：" + e.getMessage());
        }
    }

    /**
     * 开始模拟
     */
    public void startSimulation() {
        // **关键修改**：在模拟开始前准备数据，确保车辆状态初始化正确
        // prepareSimulationData();

        // 为每辆车分配运输任务
        int cargoPerTruck = calculateCargoPerTruck();

        // 创建线程安全的运输任务跟踪器
        final Map<String, Integer> trucksRemainingCargo = new ConcurrentHashMap<>();

        // 为每辆车分配任务
        for (Truck truck : trucks) {
            trucksRemainingCargo.put(truck.getTruckNo(), cargoPerTruck);
        }

        // 首次出发处理：所有车辆从出发点出发到A点
        processInitialDeparture();

        // 处理所有车辆的运输
        while (!trucksRemainingCargo.isEmpty()) {
            Map<String, Integer> nextTrucksRemainingCargo = new ConcurrentHashMap<>();

            // 创建一个包含当前所有需要运输的车辆的列表
            List<Map.Entry<String, Integer>> sortedTrucks = new ArrayList<>(trucksRemainingCargo.entrySet());

            // 按车辆的当前出发时间排序
            sortedTrucks.sort((e1, e2) -> {
                String truckNo1 = e1.getKey();
                String truckNo2 = e2.getKey();

                // 获取两辆车的当前出发时间
                LocalDateTime departureTime1 = getDepartureTime(truckNo1);
                LocalDateTime departureTime2 = getDepartureTime(truckNo2);

                // 按出发时间升序排序
                return departureTime1.compareTo(departureTime2);
            });

            // 按出发时间顺序处理每辆车
            for (Map.Entry<String, Integer> entry : sortedTrucks) {
                String truckNo = entry.getKey();
                int remainingCargoForTruck = entry.getValue();

                // 查找车辆
                Truck truck = findTruckByNo(truckNo);
                if (truck == null) continue;

                // 获取当前车辆的起始时间（首次出发已处理，直接使用上次完成时间）
                LocalDateTime currentTimeForTruck = truckCompletionTimes.get(truckNo);

                // 创建运输记录
                createTransportRecord(truckNo, currentTimeForTruck, truck.getTransportFrequency() + 1);

                LocalDateTime arrivalTime = null;
                boolean needExchange = false; // 记录是否需要换电

                if (!truckCurRouteSet.contains(truckNo)) {
                    // 使用comprehensive分析进行精确运输计算
                    arrivalTime = transportWithComprehensiveAnalysis(truck, currentTimeForTruck, true);

                    // 从B点返回A点 - 使用comprehensive分析
                    arrivalTime = transportWithComprehensiveAnalysis(truck, arrivalTime, false);
                    
                    // 检查是否在transportWithComprehensiveAnalysis中已经标记了换电
                    GeDispatchScheduleRecord currentRecord = currentTripRecords.get(truckNo);
                    if (currentRecord != null && currentRecord.getNeedExchange() != null && currentRecord.getNeedExchange() == 1) {
                        needExchange = true;
                    }
                } else {
                    RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(truck.getTruckNo(), this.truckRouteMap);

                    // 计算是否满足下一次完整运输路径的SOC消耗（使用RouteInfo中的truck信息）
                    double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC(routeInfo);

                    // 计算本趟剩余行驶公里数对应的SOC消耗
                    double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);

                    boolean needCharging = socCalculationService.shouldGoToChargingStation(truck.getSoc(), completeTransportSOC, remainingTripSOC);
                    if (needCharging) {
                        needExchange = true; // 标记需要换电
                        // 不满足下一次完整运输路径的SOC消耗，本次返程需要换电
                        // 再次判断是否满足本次运输soc消耗
                        RouteInfo routeInfo1 = BeanUtil.copyProperties(routeInfo, RouteInfo.class);
                        routeInfo1.setCurrentRoute(RouteInfo.RouteType.LOADING_TO_UNLOADING_TO_CHARGING);
                        remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo1);

                        boolean shouldGoToChargingStation = socCalculationService.shouldGoToChargingStation(truck.getSoc(), 0, remainingTripSOC);
                        if (shouldGoToChargingStation) {
                            // 不满足本次运输SOC消耗，需要立即换电再继续行驶
                            arrivalTime = transportToChargingStationThenContinue(truck, currentTimeForTruck, routeInfo);
                        } else {
                            // 满足本次运输卸货并返程到换电站进行换电
                            arrivalTime = transportCompleteCurrentRouteToCharging(truck, currentTimeForTruck, routeInfo);
                        }
                    } else {
                        // SOC充足，继续行驶（完成当前运输任务并返回装货点）
                        arrivalTime = transportCompleteCurrentRouteAndReturn(truck, currentTimeForTruck, routeInfo);
                    }
                    truckCurRouteSet.remove(truckNo);
                }

                // 完成运输记录
                completeTransportRecord(truckNo, arrivalTime, needExchange);

                // 更新车辆完成时间
                truckCompletionTimes.put(truckNo, arrivalTime);

                // 减少该车的剩余货物
                remainingCargoForTruck -= TransportService.getCargoPerTrip();

                // 如果车辆还有货物需要运输，加入下一轮
                if (remainingCargoForTruck > 0) {
                    nextTrucksRemainingCargo.put(truckNo, remainingCargoForTruck);
                }
            }

            // 更新剩余需要运输的车辆
            trucksRemainingCargo.clear();
            trucksRemainingCargo.putAll(nextTrucksRemainingCargo);
        }
    }

    /**
     * 计算每辆车需要运输的货物量
     */
    private int calculateCargoPerTruck() {
        if (trucks.isEmpty()) return 0;

        // 每辆车平均分配货物，向上取整
        BigDecimal totalCargo = BigDecimal.valueOf(TransportService.getTotalCargo());
        BigDecimal truckCount = BigDecimal.valueOf(trucks.size());
        return totalCargo.divide(truckCount, 0, RoundingMode.CEILING).intValue();
    }

    /**
     * 根据车牌号查找车辆
     */
    private Truck findTruckByNo(String truckNo) {
        for (Truck truck : trucks) {
            if (truck.getTruckNo().equals(truckNo)) {
                return truck;
            }
        }
        return null;
    }

    /**
     * 获取换电记录
     *
     * @return 换电记录列表
     */
    public List<ExchangeRecord> getExchangeRecords() {
        return exchangeStationService.getExchangeRecords();
    }

    /**
     * 获取JSON格式的换电记录
     *
     * @return JSON格式的换电记录字符串
     */
    public String getExchangeRecordsAsJson() {
        List<ExchangeRecord> records = getExchangeRecords();
        records.sort(Comparator.comparing(ExchangeRecord::getStartAwaitTime));
        return JSONUtil.toJsonStr(records);
    }

    /**
     * 获取车辆的当前出发时间
     */
    private LocalDateTime getDepartureTime(String truckNo) {
        if (truckCompletionTimes.containsKey(truckNo)) {
            // 已经完成过运输，使用上次完成时间作为下次出发时间
            return truckCompletionTimes.get(truckNo);
        } else {
            // 第一次运输，使用初始时间+偏移
            int departureOffset = truckDepartureOffsets.getOrDefault(truckNo, 0);
            return initialTime.plusMinutes(departureOffset);
        }
    }

    /**
     * 使用comprehensive分析进行精确运输计算
     *
     * @param truck       车辆
     * @param currentTime 当前时间
     * @param isAToB      true表示A到B点，false表示B到A点
     * @return 到达时间
     */
    private LocalDateTime transportWithComprehensiveAnalysis(Truck truck, LocalDateTime currentTime, boolean isAToB) {
        // 记录详细分析日志

        try {
            // 1. 分析车辆当前路径状态
            RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(truck.getTruckNo(), this.truckRouteMap);

            // 2. 计算SOC消耗
            double socConsumption;
            long driveTimeMinutes;

            if (isAToB) {
                // A到B点：满载运输 - 使用装货到卸货的SOC消耗
                // 路径1：装货点到卸货点（满载）
                socConsumption = socCalculationService.calculateRouteSOC(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM, true, getTruckCapacity(routeInfo), routeInfo.getVehicleNo());

                // 根据当前路径状态确定行驶距离
                driveTimeMinutes = calculateDriveTime(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM); // 使用动态距离获取
                // 加上装货时间
                currentTime = currentTime.plusMinutes(10); // 装货时间10分钟

                // 增加运输次数
                truck.incrementTransportFrequency();
            } else {
                // B到A点：需要判断是否需要换电
                double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC(routeInfo);
                double remainingTripSOC = socCalculationService.calculateUnloadingToLoadingRemainingSOC(routeInfo);

                // 判断是否需要换电
                boolean needCharging = socCalculationService.shouldGoToChargingStation(truck.getSoc(), completeTransportSOC, remainingTripSOC);
                if (needCharging) {
                    // 需要去换电站换电，然后返回A点
                    // 标记当前运输记录需要换电
                    GeDispatchScheduleRecord currentRecord = currentTripRecords.get(truck.getTruckNo());
                    if (currentRecord != null) {
                        currentRecord.setNeedExchange(1);
                        currentRecord.setStatusIcon("exchange");
                        currentRecord.setStatusText("返程换电");
                    }
                    return transportViaChargingStation(truck, currentTime, routeInfo);
                } else {
                    // 计算从卸货点返程到装货点的剩余SOC消耗
                    socConsumption = socCalculationService.calculateUnloadingToLoadingRemainingSOC(routeInfo);
                    driveTimeMinutes = calculateDriveTime(LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM); // 使用动态距离获取
                }
                // 加上卸货时间
                currentTime = currentTime.plusMinutes(10); // 卸货时间10分钟
            }

            // 3. 更新车辆SOC
            BigDecimal newSoc = truck.getSoc().subtract(BigDecimal.valueOf(socConsumption));
            truck.setSoc(newSoc.max(BigDecimal.ZERO)); // 确保SOC不为负数

            // 4. 计算到达时间
            LocalDateTime arrivalTime = currentTime.plusMinutes(driveTimeMinutes);

            return arrivalTime;

        } catch (Exception e) {
            // 如果comprehensive分析失败，回退到原有逻辑
            if (isAToB) {
                return transportService.transportAToB(truck, currentTime);
            } else {
                return transportService.transportBToA(truck, currentTime);
            }
        }
    }

    /**
     * 立即前往换电站然后继续当前路径
     */
    private LocalDateTime transportToChargingStationThenContinue(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            log.info("车辆 {} 立即前往换电站然后继续当前路径", truck.getTruckNo());

            // 1. 先到换电站
            long driveTimeToStationMinutes = calculateDriveTime(routeInfo, getCurrentLocationFromRouteType(routeInfo.getCurrentRoute()), "CHARGING");

            LocalDateTime arrivalTimeAtStation = currentTime.plusMinutes(driveTimeToStationMinutes);

            // 计算到换电站的SOC消耗
            double socToStation = socCalculationService.calculateRemainingTripSOC(routeInfo) * 0.3; // 估算30%用于到换电站
            BigDecimal socBeforeExchange = truck.getSoc().subtract(BigDecimal.valueOf(socToStation));
            truck.setSoc(socBeforeExchange.max(BigDecimal.ZERO));

            // 2. 进站换电
            exchangeStationService.enterStation(truck, arrivalTimeAtStation);
            LocalDateTime exchangeEndTime = findExchangeEndTime(truck.getTruckNo(), arrivalTimeAtStation,
                    socBeforeExchange, truck.getTransportFrequency());

            // 3. 换电后根据原路径类型继续运输
            RouteInfo.RouteType originalRoute = routeInfo.getCurrentRoute();
            if (originalRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING ||
                    originalRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING_TO_CHARGING) {
                // 从换电站到装货点，然后完成装货->卸货->返回装货点的完整运输
                return transportCompleteRouteFromChargingStation(truck, exchangeEndTime, routeInfo);
            } else {
                // 从换电站直接到装货点
                long driveTimeToLoadingMinutes = calculateDriveTime(routeInfo, "CHARGING", "LOADING");
                double socToLoading = socCalculationService.calculateCompleteTransportSOC(
                        LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM, truck.getTruckNo());

                truck.setSoc(BigDecimal.valueOf(LocationConstants.FULL_SOC - socToLoading));
                return exchangeEndTime.plusMinutes(driveTimeToLoadingMinutes);
            }

        } catch (Exception e) {
            log.error("车辆 {} 立即换电逻辑执行失败，回退到原有逻辑", truck.getTruckNo(), e);
            return transportService.transportStartToStationToA(truck, currentTime);
        }
    }

    /**
     * 完成当前运输任务并到达换电站
     */
    private LocalDateTime transportCompleteCurrentRouteToCharging(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            log.info("车辆 {} 完成当前运输任务并到达换电站", truck.getTruckNo());

            RouteInfo.RouteType currentRoute = routeInfo.getCurrentRoute();
            double remainingPercentage = routeInfo.getRemainingPercentage();

            // 计算当前剩余总距离
            double remainingTotalDistanceKm = remainingPercentage * routeInfo.getTotalDistanceKm();
            double totalSOCConsumption = 0.0;


            LocalDateTime arrivalTime = currentTime;
            if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING) {
                long remainingSegmentTime = calculateDriveTime(remainingTotalDistanceKm + LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM);
                totalSOCConsumption = socCalculationService.calculateLoadingToUnloadingChargingRemainingSOC(routeInfo);

                arrivalTime = arrivalTime.plusMinutes(10); // 卸货时间
                arrivalTime = arrivalTime.plusMinutes(remainingSegmentTime);
                truck.incrementTransportFrequency();

                log.debug("车辆 {} 完成满载段{}km和返程{}km，总耗时{}分钟，SOC消耗{}%", truck.getTruckNo(), remainingTotalDistanceKm, LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM, remainingSegmentTime, totalSOCConsumption);
            } else {
                // 当前已在返程段（空载）
                long returnSegmentTime = calculateDriveTime(remainingTotalDistanceKm);
                totalSOCConsumption = socCalculationService.calculateOtherRouteRemainingSOC(routeInfo);

                arrivalTime = arrivalTime.plusMinutes(returnSegmentTime);

                log.debug("车辆 {} 处于返程段，剩余距离{}km，耗时{}分钟，SOC消耗{}%", truck.getTruckNo(), remainingTotalDistanceKm, returnSegmentTime, totalSOCConsumption);
            }

            // 更新SOC
            BigDecimal newSoc = truck.getSoc().subtract(BigDecimal.valueOf(totalSOCConsumption));
            truck.setSoc(newSoc.max(BigDecimal.ZERO));

            // 进入换电站
            exchangeStationService.enterStation(truck, arrivalTime);
            LocalDateTime exchangeEndTime = findExchangeEndTime(truck.getTruckNo(), arrivalTime, truck.getSoc(), truck.getTransportFrequency());

            // 换电后从换电站到装货点
            long timeToLoading = calculateDriveTime(LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM);
            double socToLoading = socCalculationService.calculateRouteSOC(LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM, false, getTruckCapacity(routeInfo), truck.getTruckNo());

            truck.setSoc(BigDecimal.valueOf(LocationConstants.FULL_SOC - socToLoading).max(BigDecimal.ZERO));
            LocalDateTime finalArrivalTime = exchangeEndTime.plusMinutes(timeToLoading);
            log.info("车辆 {} 完成运输并换电，最终到达装货点时间：{}", truck.getTruckNo(), finalArrivalTime);

            return finalArrivalTime;

        } catch (Exception e) {
            log.error("车辆 {} 完成运输并换电失败，回退到原有逻辑", truck.getTruckNo(), e);
            return transportViaChargingStation(truck, currentTime, routeInfo);
        }
    }

    /**
     * 获取车辆电池容量
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
     * 完成当前运输任务并返回装货点（SOC充足的情况）
     */
    private LocalDateTime transportCompleteCurrentRouteAndReturn(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            log.info("车辆 {} SOC充足，完成当前运输任务并返回装货点", truck.getTruckNo());

            RouteInfo.RouteType currentRoute = routeInfo.getCurrentRoute();
            double remainingPercentage = routeInfo.getRemainingPercentage();

            // 计算当前剩余总距离
            double remainingTotalDistanceKm = remainingPercentage * routeInfo.getTotalDistanceKm();

            LocalDateTime arrivalTime = currentTime;
            double totalSOCConsumption = 0.0;

            if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING) {
                long remainingSegmentTime = calculateDriveTime(remainingTotalDistanceKm + LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM);
                totalSOCConsumption = socCalculationService.calculateRemainingTripSOC(routeInfo);

                arrivalTime = arrivalTime.plusMinutes(10); // xie货时间
                arrivalTime = arrivalTime.plusMinutes(remainingSegmentTime);
                truck.incrementTransportFrequency();

                log.debug("车辆 {} 完成满载段{}km和返程{}km，总耗时{}分钟，SOC消耗{}%", truck.getTruckNo(), remainingTotalDistanceKm, LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM, remainingSegmentTime, totalSOCConsumption);
            } else {
                // 当前已在返程段（空载）
                long returnSegmentTime = calculateDriveTime(remainingTotalDistanceKm);
                totalSOCConsumption = socCalculationService.calculateRemainingTripSOC(routeInfo);

                arrivalTime = arrivalTime.plusMinutes(returnSegmentTime);

                log.debug("车辆 {} 处于返程段，剩余距离{}km，耗时{}分钟，SOC消耗{}%", truck.getTruckNo(), remainingTotalDistanceKm, returnSegmentTime, totalSOCConsumption);
            }

            // 更新SOC
            BigDecimal newSoc = truck.getSoc().subtract(BigDecimal.valueOf(totalSOCConsumption));
            truck.setSoc(newSoc.max(BigDecimal.ZERO));

            log.info("车辆 {} 完成运输并返回装货点，到达时间：{}，剩余SOC：{}%", truck.getTruckNo(), arrivalTime, truck.getSoc());
            return arrivalTime;

        } catch (Exception e) {
            log.error("车辆 {} 完成运输并返回失败，回退到原有逻辑", truck.getTruckNo(), e);
            return transportService.transportStartToA(truck, currentTime);
        }
    }

    /**
     * 继续当前路径的运输
     */
    private LocalDateTime transportContinueCurrentRoute(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            log.info("车辆 {} 继续当前路径运输", truck.getTruckNo());

            RouteInfo.RouteType currentRoute = routeInfo.getCurrentRoute();
            double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);
            long driveTimeMinutes = calculateDriveTime(routeInfo.getRemainingDistanceKm());

            // 更新SOC
            BigDecimal newSoc = truck.getSoc().subtract(BigDecimal.valueOf(remainingTripSOC));
            truck.setSoc(newSoc.max(BigDecimal.ZERO));

            LocalDateTime arrivalTime = currentTime.plusMinutes(driveTimeMinutes);

            // 根据路径类型处理特殊逻辑
            if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING) {
                // 装货到卸货，需要增加运输次数
                truck.incrementTransportFrequency();
                // 加上装货时间
                arrivalTime = arrivalTime.plusMinutes(10);
            } else if (currentRoute == RouteInfo.RouteType.LOADING_TO_UNLOADING_TO_CHARGING) {
                // 装货到卸货再到换电站的复合路径，增加运输次数
                truck.incrementTransportFrequency();
                arrivalTime = arrivalTime.plusMinutes(10); // 装货时间
            }

            log.debug("车辆 {} 继续路径 {}，预计到达时间：{}", truck.getTruckNo(),
                    currentRoute.getDescription(), arrivalTime);

            return arrivalTime;

        } catch (Exception e) {
            log.error("车辆 {} 继续当前路径失败，回退到原有逻辑", truck.getTruckNo(), e);
            return transportService.transportStartToA(truck, currentTime);
        }
    }

    /**
     * 从换电站完成完整运输路径
     */
    private LocalDateTime transportCompleteRouteFromChargingStation(Truck truck, LocalDateTime startTime, RouteInfo routeInfo) {
        try {
            // 换电站 -> 装货点
            long timeToLoading = calculateDriveTime(routeInfo, "CHARGING", "LOADING");
            double socToLoading = socCalculationService.calculateCompleteTransportSOC(
                    LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM, truck.getTruckNo());

            LocalDateTime arrivalAtLoading = startTime.plusMinutes(timeToLoading);

            // 装货点 -> 卸货点
            arrivalAtLoading = arrivalAtLoading.plusMinutes(10); // 装货时间
            long timeToUnloading = calculateDriveTime(routeInfo, "LOADING", "UNLOADING");
            double socToUnloading = socCalculationService.calculateCompleteTransportSOC(
                    LocationConstants.LOADING_TO_UNLOADING_DISTANCE_KM, truck.getTruckNo()) * 0.6;

            LocalDateTime arrivalAtUnloading = arrivalAtLoading.plusMinutes(timeToUnloading);
            truck.incrementTransportFrequency();

            // 卸货点 -> 装货点
            long timeBackToLoading = calculateDriveTime(routeInfo, "UNLOADING", "LOADING");
            double socBackToLoading = socCalculationService.calculateCompleteTransportSOC(
                    LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM + LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM,
                    truck.getTruckNo()) * 0.4;

            // 更新最终SOC
            double totalSocConsumption = socToLoading + socToUnloading + socBackToLoading;
            truck.setSoc(BigDecimal.valueOf(LocationConstants.FULL_SOC - totalSocConsumption).max(BigDecimal.ZERO));

            return arrivalAtUnloading.plusMinutes(timeBackToLoading);

        } catch (Exception e) {
            log.error("从换电站完成完整运输失败", e);
            return startTime.plusMinutes(120); // 默认2小时后完成
        }
    }

    /**
     * 根据路径类型获取当前位置
     */
    private String getCurrentLocationFromRouteType(RouteInfo.RouteType routeType) {
        switch (routeType) {
            case START_TO_LOADING:
                return "START";
            case LOADING_TO_UNLOADING:
            case LOADING_TO_UNLOADING_TO_CHARGING:
                return "LOADING";
            case UNLOADING_TO_CHARGING:
                return "UNLOADING";
            case CHARGING_TO_LOADING:
                return "CHARGING";
            case UNLOADING_TO_LOADING:
                return "UNLOADING";
            default:
                return "START";
        }
    }

    /**
     * 经过换电站的运输
     */
    private LocalDateTime transportViaChargingStation(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        // B点到换电站的行驶时间和SOC消耗
        long driveTimeToStationMinutes = calculateDriveTime(routeInfo, "UNLOADING", "CHARGING");
        double socToStation = socCalculationService.calculateRouteSOC(LocationConstants.UNLOADING_TO_CHARGING_DISTANCE_KM, false, getTruckCapacity(routeInfo), truck.getTruckNo());

        LocalDateTime arrivalTimeAtStation = currentTime.plusMinutes(driveTimeToStationMinutes);

        // 更新SOC（到达换电站前）
        BigDecimal socBeforeExchange = truck.getSoc().subtract(BigDecimal.valueOf(socToStation));
        truck.setSoc(socBeforeExchange);

        // 进站换电
        exchangeStationService.enterStation(truck, arrivalTimeAtStation);

        // 查找换电完成时间
        LocalDateTime exchangeEndTime = findExchangeEndTime(truck.getTruckNo(), arrivalTimeAtStation, socBeforeExchange, truck.getTransportFrequency());

        // 从换电站到A点
        long driveTimeToAMinutes = calculateDriveTime(routeInfo, "CHARGING", "LOADING");
        double socToA = socCalculationService.calculateRouteSOC(LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM, false, getTruckCapacity(routeInfo), truck.getTruckNo());

        // 换电后SOC为100%，减去到A点的消耗
        BigDecimal finalSoc = BigDecimal.valueOf(100 - socToA);
        truck.setSoc(finalSoc);

        LocalDateTime finalArrivalTime = exchangeEndTime.plusMinutes(driveTimeToAMinutes);

        return finalArrivalTime;
    }

    /**
     * 计算行驶时间
     */
    private long calculateDriveTime(double distanceKm) {
        // 时间（分钟）= 距离（公里）/ 速度（公里/小时）× 60
        return (long) (distanceKm / LocationConstants.AVERAGE_SPEED * 60.0); // 平均速度48km/h
    }

    /**
     * 计算行驶时间（RouteInfo感知版本）
     *
     * @param routeInfo    路径信息（可能为null）
     * @param fromLocation 起始位置
     * @param toLocation   目标位置
     * @return 行驶时间（分钟）
     */
    private long calculateDriveTime(RouteInfo routeInfo, String fromLocation, String toLocation) {
        double distanceKm = getDistanceFromRouteInfoOrConstant(routeInfo, fromLocation, toLocation);
        return calculateDriveTime(distanceKm);
    }

    /**
     * 根据起始点和目标点计算行驶时间
     */
    private long calculateDriveTimeBetweenLocations(String fromLocation, String toLocation) {
        double distanceKm = getDistanceBetweenLocations(fromLocation, toLocation);
        return calculateDriveTime(distanceKm);
    }

    /**
     * 获取两个位置间的距离（使用LocationConstants常量）
     */
    private double getDistanceBetweenLocations(String fromLocation, String toLocation) {
        // 优先使用LocationConstants的方法
        double constantDistance = LocationConstants.getDistanceBetweenPoints(fromLocation, toLocation);
        if (constantDistance > 0) {
            return constantDistance;
        }

        // 后备硬编码值（保持原有业务逻辑）
        return getFallbackDistance(fromLocation, toLocation);
    }

    /**
     * 查找换电完成时间（简化版本）
     */
    private LocalDateTime findExchangeEndTime(String truckNo, LocalDateTime enterTime, BigDecimal socBeforeExchange, int transportFrequency) {
        // 简化实现：假设换电需要5分钟
        return exchangeStationService.getLastExchangeEndTime();
        // return enterTime.plusMinutes(5);
    }

    /**
     * 从出发点直接到A点（使用comprehensive分析）
     */
    private LocalDateTime transportStartToAWithAnalysis(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            // 计算出发点到A点的SOC消耗 - 使用精确计算
            double baseSocConsumption = socCalculationService.calculateRemainingTripSOC(routeInfo);

            // 根据路径状态确定行驶距离
            long driveTimeMinutes = calculateDriveTime(routeInfo, "START", "LOADING"); // 使用动态距离获取

            // 更新车辆SOC
            BigDecimal newSoc = truck.getSoc().subtract(BigDecimal.valueOf(baseSocConsumption));
            truck.setSoc(newSoc.max(BigDecimal.ZERO));

            LocalDateTime arrivalTime = currentTime.plusMinutes(driveTimeMinutes);

            return arrivalTime;
        } catch (Exception e) {
            // 回退到原有逻辑
            return transportService.transportStartToA(truck, currentTime);
        }
    }

    /**
     * 从出发点经换电站到A点（使用comprehensive分析）
     */
    private LocalDateTime transportStartToStationToAWithAnalysis(Truck truck, LocalDateTime currentTime, RouteInfo routeInfo) {
        try {
            // 1. 出发点到换电站
            long driveTimeToStationMinutes = calculateDriveTime(routeInfo, "START", "CHARGING");
            double baseSocConsumption = socCalculationService.calculateOtherRouteRemainingSOC(routeInfo);

            LocalDateTime arrivalTimeAtStation = currentTime.plusMinutes(driveTimeToStationMinutes);

            // 更新SOC（到达换电站前）
            BigDecimal socBeforeExchange = truck.getSoc().subtract(BigDecimal.valueOf(baseSocConsumption));
            truck.setSoc(socBeforeExchange);

            // 2. 进站换电
            exchangeStationService.enterStation(truck, arrivalTimeAtStation);
            LocalDateTime exchangeEndTime = findExchangeEndTime(truck.getTruckNo(), arrivalTimeAtStation, socBeforeExchange, truck.getTransportFrequency());

            // 3. 换电站到A点
            long driveTimeToAMinutes = calculateDriveTime(routeInfo, "CHARGING", "LOADING");
            baseSocConsumption = socCalculationService.calculateRouteSOC(LocationConstants.CHARGING_TO_LOADING_DISTANCE_KM, false, getTruckCapacity(routeInfo), truck.getTruckNo());


            // 换电后SOC为100%，减去到A点的消耗
            truck.setSoc(BigDecimal.valueOf(LocationConstants.FULL_SOC - baseSocConsumption));

            LocalDateTime finalArrivalTime = exchangeEndTime.plusMinutes(driveTimeToAMinutes);

            return finalArrivalTime;
        } catch (Exception e) {
            // 回退到原有逻辑
            return transportService.transportStartToStationToA(truck, currentTime);
        }
    }

    /**
     * 处理所有车辆的首次出发（从出发点到A点）
     */
    private void processInitialDeparture() {
        for (Truck truck : trucks) {
            // 使用comprehensive分析判断是否需要先换电
            LocalDateTime arrivalTimeAtA;
            try {

                // 分析车辆当前路径状态
                RouteInfo routeInfo = vehicleTrackingService.analyzeVehicleRoute(truck.getTruckNo(), this.truckRouteMap);
                trucks.forEach(t -> {
                    if (t.getTruckNo().equals(routeInfo.getVehicleNo())) {
                        t.setSoc(routeInfo.getCurrentSoc());
                    }
                });
                if (routeInfo.getCurrentRoute() != RouteInfo.RouteType.START_TO_LOADING) {
                    truckCompletionTimes.put(truck.getTruckNo(), routeInfo.getCurrentLocation().getReportTime());
                    truckCurRouteSet.add(truck.getTruckNo());
                    continue;
                }

                // 计算是否满足下一次完整运输路径的SOC消耗（使用RouteInfo中的truck信息）
                double completeTransportSOC = socCalculationService.calculateCompleteTransportSOC(routeInfo);

                // 计算本趟剩余行驶公里数对应的SOC消耗
                double remainingTripSOC = socCalculationService.calculateRemainingTripSOC(routeInfo);

                boolean needCharging = socCalculationService.shouldGoToChargingStation(truck.getSoc(), completeTransportSOC, remainingTripSOC);
                if (needCharging) {
                    // 需要先去换电站换电，然后到A点
                    arrivalTimeAtA = transportStartToStationToAWithAnalysis(truck, routeInfo.getCurrentLocation().getReportTime(), routeInfo);
                } else {
                    // 直接从出发点到A点
                    arrivalTimeAtA = transportStartToAWithAnalysis(truck, routeInfo.getCurrentLocation().getReportTime(), routeInfo);
                }
            } catch (Exception e) {
                // 回退到原有逻辑
                if (transportService.needsExchangeFromStart(truck)) {
                    arrivalTimeAtA = transportService.transportStartToStationToA(truck, initialTime);
                } else {
                    arrivalTimeAtA = transportService.transportStartToA(truck, initialTime);
                }
            }

            // 更新车辆完成时间（到达A点的时间）
            truckCompletionTimes.put(truck.getTruckNo(), arrivalTimeAtA);
        }
    }

    /**
     * 智能获取距离：优先从RouteInfo获取剩余距离，后备使用LocationConstants常量
     *
     * @param routeInfo    路径信息（可能为null）
     * @param fromLocation 起始位置
     * @param toLocation   目标位置
     * @return 距离（公里）
     */
    private double getDistanceFromRouteInfoOrConstant(RouteInfo routeInfo, String fromLocation, String toLocation) {
        // 优先级1：如果routeInfo可用且路径匹配，使用剩余距离
        if (routeInfo != null && routeInfo.getTargetLocation() != null &&
                routeInfo.getTargetLocation().equals(toLocation) && routeInfo.getRemainingDistanceKm() >= 0) {
            log.debug("使用RouteInfo剩余距离：{}km", routeInfo.getRemainingDistanceKm());
            return routeInfo.getRemainingDistanceKm();
        }

        // 优先级2：使用LocationConstants预定义常量
        double constantDistance = LocationConstants.getDistanceBetweenPoints(fromLocation, toLocation);
        if (constantDistance > 0) {
            log.debug("使用LocationConstants常量距离：{}km ({} -> {})", constantDistance, fromLocation, toLocation);
            return constantDistance;
        }

        // 优先级3：后备硬编码值（保持原业务逻辑）
        double fallbackDistance = getFallbackDistance(fromLocation, toLocation);
        log.warn("使用后备距离：{}km ({} -> {})", fallbackDistance, fromLocation, toLocation);
        return fallbackDistance;
    }

    /**
     * 后备距离获取（保持原有业务逻辑）
     */
    private double getFallbackDistance(String fromLocation, String toLocation) {
        if ("UNLOADING".equals(fromLocation) && "CHARGING".equals(toLocation)) {
            return 26; // B到换电站26km
        } else if ("CHARGING".equals(fromLocation) && "LOADING".equals(toLocation)) {
            return 10; // 换电站到A点10km  
        } else if ("START".equals(fromLocation) && "CHARGING".equals(toLocation)) {
            return 20; // 出发点到换电站20km
        } else if ("START".equals(fromLocation) && "LOADING".equals(toLocation)) {
            return 15; // 出发点到A点15km
        } else if ("LOADING".equals(fromLocation) && "UNLOADING".equals(toLocation)) {
            return 30; // A到B点30km
        } else if ("UNLOADING".equals(fromLocation) && "LOADING".equals(toLocation)) {
            return 30; // B到A点30km (返程)
        } else {
            return 20; // 默认20km
        }
    }

    // ===== 调度记录管理方法 =====

    /**
     * 创建运输记录
     *
     * @param truckNo       车牌号
     * @param startTime     开始时间
     * @param transportFreq 运输次数
     */
    private void createTransportRecord(String truckNo, LocalDateTime startTime, int transportFreq) {
        String scheduleDate = startTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        
        GeDispatchScheduleRecord record = GeDispatchScheduleRecord.createRecord(
            truckNo,
            "LOADING", // 装货点
            "UNLOADING", // 卸货点
            startTime,
            startTime, // 暂时设置为开始时间，完成时会更新
            false, // 初始不需要换电
            scheduleDate,
            transportFreq
        );
        
        currentTripRecords.put(truckNo, record);
        log.debug("创建运输记录：车辆{}, 运输次数{}", truckNo, transportFreq);
    }

    /**
     * 完成运输记录
     *
     * @param truckNo      车牌号
     * @param endTime      结束时间
     * @param needExchange 是否需要换电
     */
    private void completeTransportRecord(String truckNo, LocalDateTime endTime, boolean needExchange) {
        GeDispatchScheduleRecord record = currentTripRecords.get(truckNo);
        if (record != null) {
            record.setEndTime(endTime.toLocalTime());
            record.setNeedExchange(needExchange ? 1 : 0);
            
            if (needExchange) {
                record.setStatusIcon("exchange");
                record.setStatusText("需要换电");
                
                // 查找并关联换电记录
                ExchangeRecord exchangeRecord = findMatchingExchangeRecord(truckNo, record.getTransportFrequency());
                if (exchangeRecord != null) {
                    record.associateExchangeRecord(exchangeRecord);
                    log.debug("关联换电记录：车辆{}, 运输次数{}", truckNo, record.getTransportFrequency());
                }
            } else {
                record.setStatusIcon("normal");
                record.setStatusText("正常运输");
            }
            
            scheduleRecords.add(record);
            currentTripRecords.remove(truckNo);
            log.debug("完成运输记录：车辆{}, 结束时间{}, 需要换电{}", truckNo, endTime, needExchange);
        }
    }

    /**
     * 查找匹配的换电记录
     *
     * @param truckNo           车牌号
     * @param transportFrequency 运输次数
     * @return 匹配的换电记录
     */
    private ExchangeRecord findMatchingExchangeRecord(String truckNo, int transportFrequency) {
        List<ExchangeRecord> exchangeRecords = getExchangeRecords();
        
        for (ExchangeRecord record : exchangeRecords) {
            if (record.getTruckNo().equals(truckNo) && 
                record.getTransporFrequency() == transportFrequency) {
                return record;
            }
        }
        
        return null;
    }

    /**
     * 获取调度记录列表
     *
     * @return 调度记录列表
     */
    public List<GeDispatchScheduleRecord> getScheduleRecords() {
        return new ArrayList<>(scheduleRecords);
    }

    /**
     * 获取JSON格式的调度记录
     *
     * @return JSON格式的调度记录字符串
     */
    public String getScheduleRecordsAsJson() {
        List<GeDispatchScheduleRecord> records = getScheduleRecords();
        records.sort((r1, r2) -> {
            if (r1.getCreateTime() == null || r2.getCreateTime() == null) {
                return 0;
            }
            return r1.getCreateTime().compareTo(r2.getCreateTime());
        });
        return JSONUtil.toJsonStr(records);
    }
} 