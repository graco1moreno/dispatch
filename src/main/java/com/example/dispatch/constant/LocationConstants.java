package com.example.dispatch.constant;

import com.example.dispatch.model.GpsLocation;

import java.math.BigDecimal;

/**
 * 位置坐标常量类
 */
public class LocationConstants {

    /**
     * 出发点GPS位置 (深圳宝安机场附近)
     */
    public static final GpsLocation START_POINT = new GpsLocation(21.425126, 110.163891);
    static {
        START_POINT.setAddrType(1); // 1-出发点
    }

    /**
     * 装货点GPS位置 (深圳宝安)
     */
    public static final GpsLocation LOADING_POINT = new GpsLocation(21.360861, 110.050424);
    static {
        LOADING_POINT.setAddrType(2); // 2-装货点
    }

    /**
     * 卸货点GPS位置 (深圳南山)
     */
    public static final GpsLocation UNLOADING_POINT = new GpsLocation(21.425126, 110.163891);
    static {
        UNLOADING_POINT.setAddrType(3); // 3-卸货点
    }

    /**
     * 换电站GPS位置 (深圳福田)
     */
    public static final GpsLocation CHARGING_STATION = new GpsLocation(21.349973, 110.108390);
    static {
        CHARGING_STATION.setAddrType(4); // 4-换电站
    }

    /**
     * 位置判断的距离阈值（米）
     * 在此范围内认为车辆到达了目标点
     */
    public static final double LOCATION_THRESHOLD_METERS = 100.0;

    /**
     * 路径判断的置信度阈值
     * 低于此值的路径判断将被标记为不可信
     */
    public static final double CONFIDENCE_THRESHOLD = 0.6;

    /**
     * 历史轨迹时间窗口（分钟）
     */
    public static final int HISTORY_TRACK_WINDOW_MINUTES = 30;

    /**
     * GPS采样间隔（秒）
     */
    public static final int GPS_SAMPLING_INTERVAL_SECONDS = 30;

    /**
     * 默认车辆速度（km/h）- 用于缺失数据时的估算
     */
    public static final double DEFAULT_VEHICLE_SPEED_KMH = 40.0;

    /**
     * 安全裕度百分比
     */
    public static final double SAFETY_MARGIN_PERCENT = 10.0;

    /**
     * 默认电池容量（kWh）- 当RouteInfo中缺少truck或capacity信息时使用
     */
    public static final double DEFAULT_BATTERY_CAPACITY_KWH = 282;
    public static final BigDecimal DEFAULT_BATTERY_CAPACITY_DECIMAL_KWH = BigDecimal.valueOf(282);

    // 出发点到装货点的时间窗口（分）
    public static final int START_TO_LOADING_DISTANCE_DURATION = 20;

    // 预计算的关键路径距离（公里）
    public static final double LOADING_TO_UNLOADING_DISTANCE_KM = 21.3;  // 装货点到卸货点
    public static final double UNLOADING_TO_CHARGING_DISTANCE_KM = 13.7; // 卸货点到换电站
    public static final double CHARGING_TO_LOADING_DISTANCE_KM = 7.6;    // 换电站到装货点
    public static final double START_TO_LOADING_DISTANCE_KM = 21.3;      // 出发点到装货点

    /**
     * 平均时速
     */
    public static final int AVERAGE_SPEED = 40;

    public static final int FULL_SOC = 100;

    /**
     * 根据位置类型获取GpsLocation对象
     */
    public static GpsLocation getLocationByType(String locationType) {
        switch (locationType.toUpperCase()) {
            case "START":
            case "出发点":
                return START_POINT;
            case "LOADING":
            case "装货点":
                return LOADING_POINT;
            case "UNLOADING":
            case "卸货点":
                return UNLOADING_POINT;
            case "CHARGING":
            case "换电站":
                return CHARGING_STATION;
            default:
                return null;
        }
    }

    /**
     * 根据位置类型获取坐标数组 (兼容旧接口)
     */
    public static double[] getLocationCoordinates(String locationType) {
        GpsLocation location = getLocationByType(locationType);
        if (location != null) {
            return new double[]{location.getLatitude(), location.getLongitude()};
        }
        return null;
    }

    /**
     * 判断GPS位置属于哪个关键点
     */
    public static String identifyLocation(double lat, double lon) {
        GpsLocation currentPos = new GpsLocation(lat, lon);

        // 计算到各关键点的距离
        double distToLoading = currentPos.distanceTo(LOADING_POINT);
        double distToUnloading = currentPos.distanceTo(UNLOADING_POINT);
        double distToCharging = currentPos.distanceTo(CHARGING_STATION);

        // 找到最近的点
        double minDistance = Math.min(Math.min(distToLoading, distToUnloading), distToCharging);

        if (minDistance <= LOCATION_THRESHOLD_METERS) {
            if (minDistance == distToLoading) {
                return "LOADING";
            } else if (minDistance == distToUnloading) {
                return "UNLOADING";
            } else {
                return "CHARGING";
            }
        } else {
            return "IN_TRANSIT";
        }
    }

    /**
     * 计算两个GPS位置的距离（Haversine公式）
     */
    public static double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double EARTH_RADIUS = 6371008.8; // 地球半径（米）

        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLatRad = Math.toRadians(lat2 - lat1);
        double deltaLonRad = Math.toRadians(lon2 - lon1);

        double a = Math.sin(deltaLatRad / 2) * Math.sin(deltaLatRad / 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                Math.sin(deltaLonRad / 2) * Math.sin(deltaLonRad / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS * c;
    }

    /**
     * 计算两个GPS位置的距离（使用GpsLocation对象）
     */
    public static double calculateDistance(GpsLocation location1, GpsLocation location2) {
        if (location1 == null || location2 == null) {
            return Double.MAX_VALUE;
        }
        return location1.distanceTo(location2);
    }

    /**
     * 获取两个关键点之间的距离（公里）
     */
    public static double getDistanceBetweenPoints(String fromPoint, String toPoint) {
        GpsLocation fromLocation = getLocationByType(fromPoint);
        GpsLocation toLocation = getLocationByType(toPoint);

        if (fromLocation != null && toLocation != null) {
            // 使用实际计算的距离
            double distanceMeters = calculateDistance(fromLocation, toLocation);
            return distanceMeters / 1000.0; // 转换为公里
        }


        // TODO: 这里应该是初始化时传入的，不是常量写死，如果无法获取位置，使用预设值
        if ("LOADING".equals(fromPoint) && "UNLOADING".equals(toPoint)) {
            return LOADING_TO_UNLOADING_DISTANCE_KM;
        } else if ("UNLOADING".equals(fromPoint) && "CHARGING".equals(toPoint)) {
            return UNLOADING_TO_CHARGING_DISTANCE_KM;
        } else if ("CHARGING".equals(fromPoint) && "LOADING".equals(toPoint)) {
            return CHARGING_TO_LOADING_DISTANCE_KM;
        } else if ("START".equals(fromPoint) && "LOADING".equals(toPoint)) {
            return START_TO_LOADING_DISTANCE_KM;
        } else if ("UNLOADING".equals(fromPoint) && "LOADING".equals(toPoint)) {
            return UNLOADING_TO_CHARGING_DISTANCE_KM + CHARGING_TO_LOADING_DISTANCE_KM;
        }
        return 0.0;
    }
} 