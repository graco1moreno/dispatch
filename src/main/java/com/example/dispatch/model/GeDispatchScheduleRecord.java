package com.example.dispatch.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 智能排班记录VO
 * 装货点到卸货点的行驶记录
 */
@Data
public class GeDispatchScheduleRecord {

    /**
     * 记录ID
     */
    private Long id;

    /**
     * 车牌号
     */
    private String truckNo;

    /**
     * 出发地
     */
    private String fromLocation;

    /**
     * 目的地
     */
    private String toLocation;

    /**
     * 开始时间
     */
    private LocalTime startTime;

    /**
     * 结束时间
     */
    private LocalTime endTime;

    /**
     * 是否需要换电 0-无需换电 1-行程结束后前往换电
     */
    private Integer needExchange;

    /**
     * 排班日期
     */
    private String scheduleDate;

    /**
     * 状态图标标识
     */
    private String statusIcon;

    /**
     * 状态描述
     */
    private String statusText;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 换电记录
     */
    private ExchangeRecord exchangeRecord;

    /**
     * 运输次数
     */
    private Integer transportFrequency;

    public GeDispatchScheduleRecord() {
    }

    /**
     * 创建调度记录
     *
     * @param truckNo             车牌号
     * @param fromLocation        出发地
     * @param toLocation          目的地
     * @param startTime           开始时间
     * @param endTime             结束时间
     * @param needExchange        是否需要换电
     * @param scheduleDate        排班日期
     * @param transportFrequency  运输次数
     * @return 调度记录
     */
    public static GeDispatchScheduleRecord createRecord(String truckNo, String fromLocation, 
                                                       String toLocation, LocalDateTime startTime, 
                                                       LocalDateTime endTime, boolean needExchange, 
                                                       String scheduleDate, int transportFrequency) {
        GeDispatchScheduleRecord record = new GeDispatchScheduleRecord();
        record.setTruckNo(truckNo);
        record.setFromLocation(fromLocation);
        record.setToLocation(toLocation);
        record.setStartTime(startTime.toLocalTime());
        record.setEndTime(endTime.toLocalTime());
        record.setNeedExchange(needExchange ? 1 : 0);
        record.setScheduleDate(scheduleDate);
        record.setCreateTime(LocalDateTime.now());
        record.setTransportFrequency(transportFrequency);
        
        // 设置状态
        if (needExchange) {
            record.setStatusIcon("exchange");
            record.setStatusText("需要换电");
        } else {
            record.setStatusIcon("normal");
            record.setStatusText("正常运输");
        }
        
        return record;
    }

    /**
     * 关联换电记录
     *
     * @param exchangeRecord 换电记录
     */
    public void associateExchangeRecord(ExchangeRecord exchangeRecord) {
        this.exchangeRecord = exchangeRecord;
        if (exchangeRecord != null) {
            this.needExchange = 1;
            this.statusIcon = "exchange";
            this.statusText = "已换电";
        }
    }
} 