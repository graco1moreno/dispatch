package com.example.dispatch.service;

import com.example.dispatch.model.ExchangeRecord;
import com.example.dispatch.model.PricePeriod;
import com.example.dispatch.model.Truck;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 运输服务类
 */
public class TransportService {
    // 距离常量
    private static final int A_TO_B_DISTANCE = 30;  // A点到B点距离(km)
    private static final int B_TO_A_DISTANCE = 30;  // B点到A点距离(km)
    private static final int B_TO_STATION_DISTANCE = 26;  // B点到换电站距离(km)
    private static final int STATION_TO_A_DISTANCE = 10;  // 换电站到A点距离(km)
    private static final int START_TO_A_DISTANCE = 15;  // 出发点到A点距离(km)
    private static final int START_TO_STATION_DISTANCE = 20;  // 出发点到换电站距离(km)
    
    // 时间常量
    private static final int LOADING_TIME = 10;  // 装货时间(分钟)
    private static final BigDecimal AVERAGE_SPEED = BigDecimal.valueOf(80.0).setScale(2, RoundingMode.HALF_UP);  // 平均行驶速度(km/h)
    
    // 能耗常量
    private static final BigDecimal ENERGY_CONSUMPTION = BigDecimal.valueOf(1.4).setScale(2, RoundingMode.HALF_UP);  // 综合平均能耗(kWh/km)
    
    // 运输任务常量
    private static final int TOTAL_CARGO = 2000;  // 总货物量(吨)
    private static final int CARGO_PER_TRIP = 50;  // 每次运输量(吨)
    private static final BigDecimal EXCHANGE_SOC_LIMIT = BigDecimal.valueOf(35).setScale(2, RoundingMode.HALF_UP);  // 换电SOC阈值
    
    // 其他常量
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100.0).setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60.0).setScale(2, RoundingMode.HALF_UP);

    private final ExchangeStationService exchangeStationService;  // 换电站服务
    
    public TransportService(ExchangeStationService exchangeStationService) {
        this.exchangeStationService = exchangeStationService;
    }
    
    /**
     * 计算满载从A点到B点的运输时间和SOC消耗
     * @param truck 车辆
     * @param currentTime 当前时间
     * @return 运输完成时间
     */
    public LocalDateTime transportAToB(Truck truck, LocalDateTime currentTime) {
        // 装货时间
        LocalDateTime loadingEndTime = currentTime.plusMinutes(LOADING_TIME);
        
        // 计算行驶时间
        long driveTimeMinutes = calculateDriveTime(A_TO_B_DISTANCE);
        LocalDateTime arrivalTime = loadingEndTime.plusMinutes(driveTimeMinutes);
        
        // 计算SOC消耗
        BigDecimal socConsumption = calculateSocConsumption(BigDecimal.valueOf(A_TO_B_DISTANCE), truck.getCapacity());
        truck.setSoc(truck.getSoc().subtract(socConsumption).setScale(2, RoundingMode.HALF_UP));
        
        // 增加运输次数
        truck.incrementTransportFrequency();
        
        return arrivalTime;
    }
    
    /**
     * 计算下一次运输结束时间
     * @param currentTime 当前时间
     * @return 预计的下一次运输结束时间
     */
    private LocalDateTime calculateNextTripEndTime(LocalDateTime currentTime) {
        // 计算一次完整运输的时间
        long driveTimeToB = calculateDriveTime(A_TO_B_DISTANCE);
        long driveTimeToA = calculateDriveTime(B_TO_A_DISTANCE);
        long driveTimeToStation = calculateDriveTime(B_TO_STATION_DISTANCE);
        return currentTime.plusMinutes(LOADING_TIME + driveTimeToB + driveTimeToA + driveTimeToStation);
    }

    /**
     * 检查是否需要换电
     * @param truck 车辆
     * @param currentTime 当前时间
     * @param nextTripEndTime 下一次运输结束时间
     * @return 是否需要换电
     */
    private boolean needsExchange(Truck truck, LocalDateTime currentTime, LocalDateTime nextTripEndTime) {
        long driveTimeToStation = calculateDriveTime(B_TO_STATION_DISTANCE);
        LocalDateTime timeAfterDrive = currentTime.plusMinutes(driveTimeToStation);

        // 如果SOC低于最低换电阈值，必须换电
        // 使用到换电站的距离进行动态计算
        if (truck.getSoc().compareTo(ExchangeStationService.getMinExchangeSoc(B_TO_STATION_DISTANCE)) < 0) {
            return true;
        }
        
        // 如果SOC低于换电阈值，需要考虑电价因素
        if (truck.getSoc().compareTo(EXCHANGE_SOC_LIMIT) < 0) {
            // 如果当前是高电价时段，且下一次运输后会进入低电价时段，可以延后换电
            if (PricePeriod.shouldDelayExchange(timeAfterDrive, nextTripEndTime)) {
                // 计算下一次运输需要的电量
                BigDecimal nextTripConsumption = calculateSocConsumption(
                    BigDecimal.valueOf(A_TO_B_DISTANCE + B_TO_A_DISTANCE),
                    truck.getCapacity()
                );
                // 如果剩余电量足够下一次运输，则延后换电
                // 使用下次运输的距离进行动态计算
                BigDecimal nextTripDistance = BigDecimal.valueOf(A_TO_B_DISTANCE + B_TO_A_DISTANCE);
                return truck.getSoc().subtract(nextTripConsumption).compareTo(ExchangeStationService.getMinExchangeSoc(nextTripDistance)) < 0;
            }
            return true;
        }
        
        // 如果当前是低电价时段，且下一次运输会进入高电价时段，考虑提前换电
        if (PricePeriod.shouldExchangeEarly(timeAfterDrive, nextTripEndTime)) {
            // 检查是否有可用电池且等待队列较短
            return exchangeStationService.canExchangeEarly(timeAfterDrive);
        }
        
        return false;
    }

    public LocalDateTime transportBToA(Truck truck, LocalDateTime currentTime) {
        // 计算下一次运输结束时间
        LocalDateTime nextTripEndTime = calculateNextTripEndTime(currentTime);
        
        // 检查是否需要换电
        if (needsExchange(truck, currentTime, nextTripEndTime)) {
            // 需要去换电站换电
            return transportBToStationToA(truck, currentTime);
        } else {
            // 直接返回A点
            // 计算行驶时间
            long driveTimeMinutes = calculateDriveTime(B_TO_A_DISTANCE);
            LocalDateTime arrivalTime = currentTime.plusMinutes(driveTimeMinutes);
            
            // 计算SOC消耗
            BigDecimal socConsumption = calculateSocConsumption(BigDecimal.valueOf(B_TO_A_DISTANCE), truck.getCapacity());
            truck.setSoc(truck.getSoc().subtract(socConsumption).setScale(2, RoundingMode.HALF_UP));
            
            return arrivalTime;
        }
    }
    
    /**
     * 计算从B点经过换电站返回A点的运输时间和SOC消耗
     * @param truck 车辆
     * @param currentTime 当前时间
     * @return 运输完成时间
     */
    private LocalDateTime transportBToStationToA(Truck truck, LocalDateTime currentTime) {
        // 1. 计算B点到换电站的行驶时间
        long driveTimeToStationMinutes = calculateDriveTime(B_TO_STATION_DISTANCE);
        LocalDateTime arrivalTimeAtStation = currentTime.plusMinutes(driveTimeToStationMinutes);
        
        // 2. 计算B点到换电站的SOC消耗
        BigDecimal socConsumptionToStation = calculateSocConsumption(BigDecimal.valueOf(B_TO_STATION_DISTANCE), truck.getCapacity());
        BigDecimal socBeforeExchange = truck.getSoc().subtract(socConsumptionToStation).setScale(2, RoundingMode.HALF_UP);
        truck.setSoc(socBeforeExchange);
        
        // 3. 记录当前运输次数（用于后续找到对应的换电记录）
        int transportFrequency = truck.getTransportFrequency();
        
        // 4. 车辆进站换电
        exchangeStationService.enterStation(truck, arrivalTimeAtStation);
        
        // 5. 查找换电记录，确定实际换电完成时间
        LocalDateTime exchangeEndTime = findExchangeEndTime(truck.getTruckNo(), arrivalTimeAtStation, socBeforeExchange, transportFrequency);
        
        // 6. 换电完成后，计算从换电站到A点的行驶时间
        long driveTimeToAMinutes = calculateDriveTime(STATION_TO_A_DISTANCE);
        LocalDateTime arrivalTimeAtA = exchangeEndTime.plusMinutes(driveTimeToAMinutes);
        
        // 7. 计算换电站到A点的SOC消耗（换电完成后SOC为100%）
        BigDecimal socConsumptionToA = calculateSocConsumption(BigDecimal.valueOf(STATION_TO_A_DISTANCE), truck.getCapacity());
        truck.setSoc(HUNDRED.subtract(socConsumptionToA).setScale(2, RoundingMode.HALF_UP));
        
        return arrivalTimeAtA;
    }
    
    /**
     * 查找车辆换电完成时间
     * @param truckNo 车牌号
     * @param enterTime 进站时间
     * @param socBeforeExchange 换电前SOC
     * @param transportFrequency 运输次数
     * @return 换电完成时间
     */
    private LocalDateTime findExchangeEndTime(String truckNo, LocalDateTime enterTime, BigDecimal socBeforeExchange, int transportFrequency) {
        // 获取所有换电记录
        List<ExchangeRecord> records = exchangeStationService.getExchangeRecords();
        
        // 查找对应车辆的换电记录
        for (ExchangeRecord record : records) {
            if (record.getTruckNo().equals(truckNo) && 
                record.getSoc().subtract(socBeforeExchange).abs().compareTo(BigDecimal.valueOf(0.01)) < 0 && 
                record.getTransporFrequency() == transportFrequency) {
                // 换电完成时间 = 开始换电时间 + 换电时长(5分钟)
                return record.getStartExchangeTime().plusMinutes(5);
            }
        }
        
        // 默认情况，返回进站时间+10分钟(考虑最坏情况)
        return enterTime.plusMinutes(10);
    }
    
    /**
     * 计算行驶时间
     * @param distance 距离(km)
     * @return 行驶时间(分钟)
     */
    private long calculateDriveTime(int distance) {
        BigDecimal distanceBD = BigDecimal.valueOf(distance);
        BigDecimal result = distanceBD.divide(AVERAGE_SPEED, 2, RoundingMode.HALF_UP).multiply(SIXTY);
        return result.setScale(0, RoundingMode.CEILING).longValue();
    }
    
    /**
     * 计算SOC消耗
     * @param distance 距离(km)
     * @param capacity 电池容量(kWh)
     * @return SOC消耗百分比
     */
    private BigDecimal calculateSocConsumption(BigDecimal distance, BigDecimal capacity) {
        return distance.multiply(ENERGY_CONSUMPTION)
            .divide(capacity, 2, RoundingMode.HALF_UP)
            .multiply(HUNDRED)
            .setScale(2, RoundingMode.HALF_UP);
    }
    
    /**
     * 获取总货物量
     * @return 总货物量(吨)
     */
    public static int getTotalCargo() {
        return TOTAL_CARGO;
    }
    
    /**
     * 获取每次运输量
     * @return 每次运输量(吨)
     */
    public static int getCargoPerTrip() {
        return CARGO_PER_TRIP;
    }
    
    /**
     * 判断车辆从出发点开始是否需要先换电
     * @param truck 车辆
     * @return 是否需要先换电
     */
    public boolean needsExchangeFromStart(Truck truck) {
        // 检查SOC是否低于安全阈值
        BigDecimal socNeededToA = calculateSocConsumption(BigDecimal.valueOf(START_TO_A_DISTANCE), truck.getCapacity());
        BigDecimal socNeededToB = calculateSocConsumption(BigDecimal.valueOf(A_TO_B_DISTANCE), truck.getCapacity());
        BigDecimal socNeededTotal = socNeededToA.add(socNeededToB);
        
        // 如果剩余SOC低于去A点加去B点再加10%裕度的SOC，则需要先换电
        return truck.getSoc().compareTo(socNeededTotal.add(BigDecimal.TEN)) < 0;
    }
    
    /**
     * 计算从出发点到A点的运输时间和SOC消耗
     * @param truck 车辆
     * @param currentTime 当前时间
     * @return 运输完成时间
     */
    public LocalDateTime transportStartToA(Truck truck, LocalDateTime currentTime) {
        // 计算行驶时间
        long driveTimeMinutes = calculateDriveTime(START_TO_A_DISTANCE);
        LocalDateTime arrivalTime = currentTime.plusMinutes(driveTimeMinutes);
        
        // 计算SOC消耗
        BigDecimal socConsumption = calculateSocConsumption(BigDecimal.valueOf(START_TO_A_DISTANCE), truck.getCapacity());
        truck.setSoc(truck.getSoc().subtract(socConsumption).setScale(2, RoundingMode.HALF_UP));
        
        return arrivalTime;
    }
    
    /**
     * 计算从出发点经过换电站到A点的运输时间和SOC消耗
     * @param truck 车辆
     * @param currentTime 当前时间
     * @return 运输完成时间
     */
    public LocalDateTime transportStartToStationToA(Truck truck, LocalDateTime currentTime) {
        // 1. 计算出发点到换电站的行驶时间
        long driveTimeToStationMinutes = calculateDriveTime(START_TO_STATION_DISTANCE);
        LocalDateTime arrivalTimeAtStation = currentTime.plusMinutes(driveTimeToStationMinutes);
        
        // 2. 计算出发点到换电站的SOC消耗
        BigDecimal socConsumptionToStation = calculateSocConsumption(BigDecimal.valueOf(START_TO_STATION_DISTANCE), truck.getCapacity());
        BigDecimal socBeforeExchange = truck.getSoc().subtract(socConsumptionToStation).setScale(2, RoundingMode.HALF_UP);
        truck.setSoc(socBeforeExchange);
        
        // 3. 记录当前运输次数（用于后续找到对应的换电记录）
        int transportFrequency = truck.getTransportFrequency();
        
        // 4. 车辆进站换电
        exchangeStationService.enterStation(truck, arrivalTimeAtStation);
        
        // 5. 查找换电记录，确定实际换电完成时间
        LocalDateTime exchangeEndTime = findExchangeEndTimeFromStart(truck.getTruckNo(), arrivalTimeAtStation, socBeforeExchange, transportFrequency);
        
        // 6. 换电完成后，计算从换电站到A点的行驶时间
        long driveTimeToAMinutes = calculateDriveTime(STATION_TO_A_DISTANCE);
        LocalDateTime arrivalTimeAtA = exchangeEndTime.plusMinutes(driveTimeToAMinutes);
        
        // 7. 计算换电站到A点的SOC消耗（换电完成后SOC为100%）
        BigDecimal socConsumptionToA = calculateSocConsumption(BigDecimal.valueOf(STATION_TO_A_DISTANCE), truck.getCapacity());
        truck.setSoc(HUNDRED.subtract(socConsumptionToA).setScale(2, RoundingMode.HALF_UP));
        
        return arrivalTimeAtA;
    }
    
    /**
     * 查找车辆换电完成时间（从出发点开始）
     * @param truckNo 车牌号
     * @param enterTime 进站时间
     * @param socBeforeExchange 换电前SOC
     * @param transportFrequency 运输次数
     * @return 换电完成时间
     */
    private LocalDateTime findExchangeEndTimeFromStart(String truckNo, LocalDateTime enterTime, BigDecimal socBeforeExchange, int transportFrequency) {
        // 获取所有换电记录
        List<ExchangeRecord> records = exchangeStationService.getExchangeRecords();
        
        // 查找对应车辆的换电记录
        for (ExchangeRecord record : records) {
            if (record.getTruckNo().equals(truckNo) && 
                record.getSoc().subtract(socBeforeExchange).abs().compareTo(BigDecimal.valueOf(0.01)) < 0 && 
                record.getTransporFrequency() == transportFrequency) {
                // 换电完成时间 = 开始换电时间 + 换电时长(5分钟)
                return record.getStartExchangeTime().plusMinutes(5);
            }
        }
        
        // 默认情况，返回进站时间+10分钟(考虑最坏情况)
        return enterTime.plusMinutes(10);
    }
} 