package com.example.dispatch.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 车辆行驶记录实体
 */
@Data
public class GeTruckDrivingRecord {

    /**
     * 主键
     */
    private Long id;

    /**
     * 车辆识别号 (VIN)
     */
    private String vin;

    /**
     * 车牌号
     */
    private String truckNo;

    /**
     * 所属车队ID
     */
    private Long copId;

    /**
     * 所属车队名称
     */
    private String copName;

    /**
     * 上报时间
     */
    private LocalDateTime reportTime;

    /**
     * 车辆状态：1-启动 2-熄火
     */
    private Integer engineStatus;

    /**
     * 车速 (km/h)
     */
    private BigDecimal speed;

    /**
     * 累计里程 (km)
     */
    private BigDecimal totalDrivingKm;

    /**
     * 电池SOC (%)
     */
    private BigDecimal soc;

    /**
     * 累计电耗 (kWh)
     */
    private BigDecimal totalPowerConsumption;

    /**
     * 平均电耗(kwh/100km)
     */
    private BigDecimal averagePowerConsumption;

    /**
     * 纬度
     */
    private BigDecimal lat;

    /**
     * 经度
     */
    private BigDecimal lon;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 数据来源 CHENG_LONG
     */
    private String source;

    private String orderNo;
}
