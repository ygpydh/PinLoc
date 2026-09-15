package com.pinloc.app;

/**
 * 坐标工具（v2.0 重构）：范围校验、GCJ02↔WGS84 转换、距离/方向计算。
 * 从 MainActivity 中抽出，纯静态方法，无 Android 依赖。
 */
public final class CoordinateUtils {

    private static final double PI = Math.PI;
    private static final double A = 6378245.0;           // 长半轴
    private static final double EE = 0.00669342162296594323; // 偏心率平方

    private CoordinateUtils() {
    }

    /** 纬度范围校验：-90 ~ 90 */
    public static boolean isValidLat(double lat) {
        return lat >= -90.0 && lat <= 90.0;
    }

    /** 经度范围校验：-180 ~ 180 */
    public static boolean isValidLon(double lon) {
        return lon >= -180.0 && lon <= 180.0;
    }

    /** 格式化坐标（6 位小数） */
    public static String format(double lat, double lon) {
        return String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon);
    }

    // ================= GCJ02 ↔ WGS84 =================

    /** GCJ-02（高德/腾讯/谷歌中国）→ WGS84（GPS/OSM/Carto）。迭代反算，市区约 1m 级。 */
    public static double[] gcj02ToWgs84(double gcjLat, double gcjLon) {
        if (outOfChina(gcjLat, gcjLon)) {
            return new double[]{gcjLat, gcjLon};
        }
        double wgsLat = gcjLat;
        double wgsLon = gcjLon;
        for (int i = 0; i < 3; i++) {
            double[] again = wgs84ToGcj02(wgsLat, wgsLon);
            wgsLat -= again[0] - gcjLat;
            wgsLon -= again[1] - gcjLon;
        }
        return new double[]{wgsLat, wgsLon};
    }

    /** WGS84 → GCJ-02（选高德底图时用） */
    public static double[] wgs84ToGcj02(double wgsLat, double wgsLon) {
        if (outOfChina(wgsLat, wgsLon)) {
            return new double[]{wgsLat, wgsLon};
        }
        double[] d = delta(wgsLat, wgsLon);
        return new double[]{wgsLat + d[0], wgsLon + d[1]};
    }

    private static boolean outOfChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }

    private static double[] delta(double lat, double lon) {
        double dLat = transformLat(lon - 105.0, lat - 35.0);
        double dLon = transformLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{dLat, dLon};
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    // ================= 距离/方向 =================

    /** 两点间距离（米，Haversine） */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** 按方向角（度，0=北）和距离（米）偏移坐标 */
    public static double[] offset(double lat, double lon, double bearingDeg, double meters) {
        double d = meters / 111320.0;
        double rad = Math.toRadians(bearingDeg);
        double dLat = d * Math.cos(rad);
        double dLon = d * Math.sin(rad) / Math.max(0.01, Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }
}
