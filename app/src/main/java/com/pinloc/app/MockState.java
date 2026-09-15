package com.pinloc.app;

import android.location.GnssStatus;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 模拟位置状态机（运行于被注入的系统进程）。
 *
 * 职责：
 *   1. 维护模拟参数（坐标/速度/方向/海拔/精度）与开关状态；
 *   2. overlay() 返回钉在选点的坐标；未开启时返回 null（放行真实定位）；
 *   3. handleCommand() 处理来自模块 App 的跨进程命令
 *      （经 LocationManager.sendExtraCommand）。
 *
 * 跨进程开关同步（重要）：
 *   命令通道只到达 system_server，而 PhoneHook / FusedHook / BluetoothHook
 *   运行在 com.android.phone / fused / bluetooth 进程——这些进程的静态副本
 *   收不到命令。因此 setEnabled() 同时把开关写入系统属性
 *   debug.pinloc.enabled（system_server 对 debug.* 属性域有写权限）。
 *
 *   方向是**单向广播**：system_server 是命令权威进程（由 PinLocModule 置
 *   authorityProcess=true），isEnabled() 只用本地状态——命令直达、可靠，
 *   即使属性写入被 SELinux 拒绝也绝不会反向覆盖开关（保证 start/stop/
 *   坐标更新永远生效）。只有子进程（phone/fused/bluetooth）读属性同步，
 *   写入失败时子进程伪造降级，不影响核心功能。
 *
 * 线程安全：全部访问经 LOCK 同步；Hook 侧多线程并发调用安全。
 */
public final class MockState {

    /** 命令通道标识（与 LocationManager.sendExtraCommand 第一参一致） */
    public static final String CHANNEL = "pinloc";

    // ---- 命令动作 ----
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_UPDATE_LOCATION = "update_location";
    public static final String ACTION_MOVE = "move";
    public static final String ACTION_PUT_CONFIG = "put_config";
    public static final String ACTION_SET_BEARING = "set_bearing";
    public static final String ACTION_IS_START = "is_start";

    // ---- extras 字段名 ----
    public static final String KEY_LAT = "lat";
    public static final String KEY_LON = "lon";
    public static final String KEY_SPEED = "speed";
    public static final String KEY_ALTITUDE = "altitude";
    public static final String KEY_ACCURACY = "accuracy";
    public static final String KEY_BEARING = "bearing";
    public static final String KEY_SATELLITES = "satellites";
    public static final String KEY_MAX_CN0 = "max_cn0";
    public static final String KEY_MEAN_CN0 = "mean_cn0";
    /** 为 true 表示该字段留空，从真实 Location 拷贝 */
    public static final String KEY_HAS_ALTITUDE = "has_altitude";
    public static final String KEY_HAS_ACCURACY = "has_accuracy";
    public static final String KEY_HAS_SPEED = "has_speed";
    public static final String KEY_HAS_BEARING = "has_bearing";
    public static final String KEY_HAS_SATELLITES = "has_satellites";
    public static final String KEY_HAS_MAX_CN0 = "has_max_cn0";
    public static final String KEY_HAS_MEAN_CN0 = "has_mean_cn0";
    /** Hook 回写：命令已被框架处理（不要用 sendExtraCommand 的系统返回值当开关） */
    public static final String KEY_ACK = "ack";
    /** Hook 回写：当前是否正在模拟 */
    public static final String KEY_STARTED = "started";

    /** 跨进程开关同步的系统属性（system_server 写，各注入进程读） */
    private static final String PROP_ENABLED = "debug.pinloc.enabled";

    /** 模块激活标记（system_server hook 安装成功后写入，App 侧读取判断模块是否激活） */
    private static final String PROP_MODULE_ACTIVE = "debug.pinloc.active";

    /** 属性刷新间隔：开关变化在 200ms 内传播到所有注入进程 */
    private static final long PROP_SYNC_INTERVAL_MS = 200L;

    /** 轨迹推进的单步时间上限（秒）：系统休眠/挂起后避免坐标跳变 */
    private static final double MAX_MOVE_STEP_SEC = 10.0;

    /** 模拟开关 */
    private static boolean enabled = false;

    /**
     * 当前进程是否为命令权威进程（system_server，命令直达）。
     * 由 PinLocModule 在入口回调中设置：system_server → true，其他注入进程 → false。
     * 权威进程只用本地状态；子进程以系统属性为跨进程真源（单向读）。
     */
    private static volatile boolean authorityProcess = true;

    /** 目标参数 */
    private static double lat = 39.9042;        // 默认：北京
    private static double lon = 116.4074;
    private static double altitude = 80.0;
    private static float accuracy = 25.0f;
    private static float speed = 0.0f;
    private static float bearing = 0.0f;
    private static boolean hasAltitude = false;
    private static boolean hasAccuracy = false;
    private static boolean hasSpeed = false;
    private static boolean hasBearing = false;
    private static int satellites = 12;
    private static int maxCn0 = 38;
    private static int meanCn0 = 28;
    private static boolean hasSatellites = false;
    private static boolean hasMaxCn0 = false;
    private static boolean hasMeanCn0 = false;

    /** 已推进坐标与上次推进时间 */
    private static double curLat = lat;
    private static double curLon = lon;
    private static long lastMoveTime = 0L;

    private static final Object LOCK = new Object();

    /** 系统属性反射句柄（android.os.SystemProperties 为隐藏 API，反射调用） */
    private static Method PROP_GET;
    private static Method PROP_SET;
    private static volatile long lastPropSync = 0L;

    static {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            PROP_GET = sp.getMethod("get", String.class);
            PROP_SET = sp.getMethod("set", String.class, String.class);
        } catch (Throwable t) {
            // 属性不可用则退回纯本地开关（system_server 内仍完全可用）
            PROP_GET = null;
            PROP_SET = null;
        }
    }

    private MockState() {
    }

    // ================= 状态读取 =================

    /** 设置当前进程是否命令权威进程（由 PinLocModule 调用） */
    public static void setAuthorityProcess(boolean authoritative) {
        authorityProcess = authoritative;
    }

    public static boolean isEnabled() {
        synchronized (LOCK) {
            // 只有子进程从属性同步；权威进程（system_server）本地状态即真源，
            // 保证 stop/start/坐标更新不会被过期属性值反向覆盖
            if (!authorityProcess) {
                syncFromProperty();
            }
            return enabled;
        }
    }

    /** system_server hook 安装成功后调用，写入模块激活标记 */
    public static void markModuleActive() {
        if (PROP_SET != null) {
            try {
                PROP_SET.invoke(null, PROP_MODULE_ACTIVE, "1");
            } catch (Throwable ignored) {}
        }
    }

    /** App 侧调用，读取系统属性判断模块是否已激活（hook 是否安装成功） */
    public static boolean isModuleActive() {
        if (PROP_GET != null) {
            try {
                String v = (String) PROP_GET.invoke(null, PROP_MODULE_ACTIVE);
                return "1".equals(v);
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** 从系统属性刷新开关（其他进程无法收到命令，以属性为跨进程真源） */
    private static void syncFromProperty() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastPropSync < PROP_SYNC_INTERVAL_MS || PROP_GET == null) {
            return;
        }
        lastPropSync = now;
        try {
            String v = (String) PROP_GET.invoke(null, PROP_ENABLED);
            if ("1".equals(v)) {
                enabled = true;
            } else if ("0".equals(v)) {
                enabled = false;
            }
        } catch (Throwable t) {
            // 读取失败保持本地状态
        }
    }

    public static double targetLat() {
        synchronized (LOCK) {
            return lat;
        }
    }

    public static double targetLon() {
        synchronized (LOCK) {
            return lon;
        }
    }

    public static double currentLat() {
        synchronized (LOCK) {
            return curLat;
        }
    }

    public static double currentLon() {
        synchronized (LOCK) {
            return curLon;
        }
    }

    public static float speed() {
        synchronized (LOCK) {
            return speed;
        }
    }

    public static double altitude() {
        synchronized (LOCK) {
            return altitude;
        }
    }

    public static float accuracy() {
        synchronized (LOCK) {
            return accuracy;
        }
    }

    public static float bearing() {
        synchronized (LOCK) {
            return bearing;
        }
    }

    // ================= 命令处理（框架进程调用） =================

    /**
     * 处理模块 App 经 sendExtraCommand 下发的命令。
     * 成功时回写 extras.ack / extras.started（AIDL inout Bundle，客户端可读）。
     *
     * @return 命令是否被识别并执行（不再把 is_start 的开关状态当作方法返回值）
     */
    public static boolean handleCommand(Bundle extras) {
        if (extras == null) {
            return false;
        }
        String action = extras.getString("action");
        if (action == null) {
            return false;
        }
        boolean handled;
        switch (action) {
            case ACTION_START:
                applyIfPresent(extras);
                setEnabled(true);
                handled = true;
                break;
            case ACTION_STOP:
                setEnabled(false);
                handled = true;
                break;
            case ACTION_IS_START:
                handled = true;
                break;
            case ACTION_UPDATE_LOCATION:
            case ACTION_MOVE:
                handled = handleUpdateLocation(extras);
                break;
            case ACTION_PUT_CONFIG:
                handled = handlePutConfig(extras);
                break;
            case ACTION_SET_BEARING:
                handled = handleSetBearing(extras);
                break;
            default:
                handled = false;
                break;
        }
        if (handled) {
            extras.putBoolean(KEY_ACK, true);
            extras.putBoolean(KEY_STARTED, isEnabled());
        }
        return handled;
    }

    /** 应用 extras 中存在的参数（缺省保留原值） */
    private static boolean applyIfPresent(Bundle extras) {
        boolean any = false;
        synchronized (LOCK) {
            if (extras.containsKey(KEY_LAT)) {
                lat = extras.getDouble(KEY_LAT);
                any = true;
            }
            if (extras.containsKey(KEY_LON)) {
                lon = extras.getDouble(KEY_LON);
                any = true;
            }
            if (applyCamouflageLocked(extras)) {
                any = true;
            }
        }
        return any;
    }

    private static boolean handleUpdateLocation(Bundle extras) {
        boolean hasLat = extras.containsKey(KEY_LAT);
        boolean hasLon = extras.containsKey(KEY_LON);
        if (!hasLat && !hasLon) {
            return false;
        }
        synchronized (LOCK) {
            if (hasLat) {
                lat = extras.getDouble(KEY_LAT);
            }
            if (hasLon) {
                lon = extras.getDouble(KEY_LON);
            }
            // move 语义：立即跳转到新坐标；update_location 语义：后续轨迹推进到目标
            if (ACTION_MOVE.equals(extras.getString("action"))) {
                curLat = lat;
                curLon = lon;
            }
            lastMoveTime = 0L;
            return true;
        }
    }

    private static boolean handlePutConfig(Bundle extras) {
        synchronized (LOCK) {
            return applyCamouflageLocked(extras);
        }
    }

    /** 调用方须持有 LOCK。空白字段用 has_*=false 表示跟真实。 */
    private static boolean applyCamouflageLocked(Bundle extras) {
        boolean any = false;
        if (extras.containsKey(KEY_HAS_ALTITUDE)) {
            hasAltitude = extras.getBoolean(KEY_HAS_ALTITUDE);
            if (hasAltitude) {
                altitude = extras.getDouble(KEY_ALTITUDE);
            }
            any = true;
        } else if (extras.containsKey(KEY_ALTITUDE)) {
            hasAltitude = true;
            altitude = extras.getDouble(KEY_ALTITUDE);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_ACCURACY)) {
            hasAccuracy = extras.getBoolean(KEY_HAS_ACCURACY);
            if (hasAccuracy) {
                accuracy = extras.getFloat(KEY_ACCURACY);
            }
            any = true;
        } else if (extras.containsKey(KEY_ACCURACY)) {
            hasAccuracy = true;
            accuracy = extras.getFloat(KEY_ACCURACY);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_SPEED)) {
            hasSpeed = extras.getBoolean(KEY_HAS_SPEED);
            if (hasSpeed) {
                speed = extras.getFloat(KEY_SPEED);
            }
            any = true;
        } else if (extras.containsKey(KEY_SPEED)) {
            hasSpeed = true;
            speed = extras.getFloat(KEY_SPEED);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_BEARING)) {
            hasBearing = extras.getBoolean(KEY_HAS_BEARING);
            if (hasBearing) {
                bearing = extras.getFloat(KEY_BEARING);
            }
            any = true;
        } else if (extras.containsKey(KEY_BEARING)) {
            hasBearing = true;
            bearing = extras.getFloat(KEY_BEARING);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_SATELLITES)) {
            hasSatellites = extras.getBoolean(KEY_HAS_SATELLITES);
            if (hasSatellites) {
                satellites = extras.getInt(KEY_SATELLITES);
            }
            any = true;
        } else if (extras.containsKey(KEY_SATELLITES)) {
            hasSatellites = true;
            satellites = extras.getInt(KEY_SATELLITES);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_MAX_CN0)) {
            hasMaxCn0 = extras.getBoolean(KEY_HAS_MAX_CN0);
            if (hasMaxCn0) {
                maxCn0 = extras.getInt(KEY_MAX_CN0);
            }
            any = true;
        } else if (extras.containsKey(KEY_MAX_CN0)) {
            hasMaxCn0 = true;
            maxCn0 = extras.getInt(KEY_MAX_CN0);
            any = true;
        }
        if (extras.containsKey(KEY_HAS_MEAN_CN0)) {
            hasMeanCn0 = extras.getBoolean(KEY_HAS_MEAN_CN0);
            if (hasMeanCn0) {
                meanCn0 = extras.getInt(KEY_MEAN_CN0);
            }
            any = true;
        } else if (extras.containsKey(KEY_MEAN_CN0)) {
            hasMeanCn0 = true;
            meanCn0 = extras.getInt(KEY_MEAN_CN0);
            any = true;
        }
        return any;
    }

    private static boolean handleSetBearing(Bundle extras) {
        synchronized (LOCK) {
            return applyCamouflageLocked(extras);
        }
    }

    /** 应用一组完整参数（UI 一次性下发用） */
    public static void apply(double newLat, double newLon, double newAlt,
                             float newSpeed, float newAccuracy, float newBearing) {
        synchronized (LOCK) {
            lat = newLat;
            lon = newLon;
            altitude = newAlt;
            speed = newSpeed;
            accuracy = newAccuracy;
            bearing = newBearing;
            curLat = newLat;
            curLon = newLon;
            lastMoveTime = 0L;
        }
    }

    /** 启动 / 停止模拟（同时写系统属性，供其他注入进程同步） */
    public static void setEnabled(boolean on) {
        synchronized (LOCK) {
            enabled = on;
            lastMoveTime = 0L;
            if (on) {
                curLat = lat;
                curLon = lon;
            }
            if (PROP_SET != null) {
                try {
                    PROP_SET.invoke(null, PROP_ENABLED, on ? "1" : "0");
                } catch (Throwable t) {
                    // 写入失败则其他进程无法同步（本地状态不受影响）
                }
            }
            lastPropSync = SystemClock.elapsedRealtime();
        }
    }

    public static Location buildLocation() {
        return overlay(null);
    }

    /**
     * 假坐标钉在选点；海拔/精度/速度/方位/卫星 extras：
     * 设置了就用设置，没设置则从 real 拷贝。real 为空则该字段不硬填默认。
     */
    public static Location overlay(Location real) {
        synchronized (LOCK) {
            if (!enabled) {
                return null;
            }
            long now = System.currentTimeMillis();
            lastMoveTime = now;
            Location loc = real != null ? new Location(real) : new Location("gps");
            loc.setProvider("gps");
            loc.setLatitude(curLat);
            loc.setLongitude(curLon);
            loc.setTime(now);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            if (hasAltitude) {
                loc.setAltitude(altitude);
            } else if (real != null && real.hasAltitude()) {
                loc.setAltitude(real.getAltitude());
            }

            if (hasAccuracy) {
                loc.setAccuracy(accuracy);
            } else if (real != null && real.hasAccuracy()) {
                loc.setAccuracy(real.getAccuracy());
            }

            if (hasSpeed) {
                loc.setSpeed(speed);
            } else if (real != null && real.hasSpeed()) {
                loc.setSpeed(real.getSpeed());
            }

            if (hasBearing) {
                loc.setBearing((float) (((bearing % 360.0) + 360.0) % 360.0));
            } else if (real != null && real.hasBearing()) {
                loc.setBearing(real.getBearing());
            }

            applyOptionalAccuracies(loc, real);
            applyExtras(loc, real);
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    loc.setMock(false);
                } catch (Throwable ignored) {}
            }
            try {
                Method makeComplete = Location.class.getMethod("makeComplete");
                makeComplete.invoke(loc);
            } catch (Throwable ignored) {}
            return loc;
        }
    }

    private static void applyOptionalAccuracies(Location loc, Location real) {
        if (hasSpeed && loc.hasSpeed()) {
            try {
                loc.setSpeedAccuracyMetersPerSecond(Math.max(0.3f, Math.abs(loc.getSpeed()) * 0.15f));
            } catch (Throwable ignored) {}
        } else if (real != null) {
            try {
                if (real.hasSpeedAccuracy()) {
                    loc.setSpeedAccuracyMetersPerSecond(real.getSpeedAccuracyMetersPerSecond());
                }
            } catch (Throwable ignored) {}
        }
        if (hasBearing && loc.hasBearing()) {
            try {
                float b = loc.getBearing();
                loc.setBearingAccuracyDegrees(b == 0.0f ? 1.0f : Math.max(1.0f, loc.getAccuracy() / 5.0f));
            } catch (Throwable ignored) {}
        } else if (real != null) {
            try {
                if (real.hasBearingAccuracy()) {
                    loc.setBearingAccuracyDegrees(real.getBearingAccuracyDegrees());
                }
            } catch (Throwable ignored) {}
        }
        if (hasAccuracy) {
            try {
                loc.setVerticalAccuracyMeters(accuracy);
            } catch (Throwable ignored) {}
        } else if (real != null) {
            try {
                if (real.hasVerticalAccuracy()) {
                    loc.setVerticalAccuracyMeters(real.getVerticalAccuracyMeters());
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (real != null) {
                    loc.setElapsedRealtimeUncertaintyNanos(real.getElapsedRealtimeUncertaintyNanos());
                }
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= 34 && loc.hasAltitude()) {
            try {
                loc.setMslAltitudeMeters(loc.getAltitude());
                if (loc.hasVerticalAccuracy()) {
                    loc.setMslAltitudeAccuracyMeters(loc.getVerticalAccuracyMeters());
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void applyExtras(Location loc, Location real) {
        Bundle extras = null;
        if (real != null && real.getExtras() != null) {
            extras = new Bundle(real.getExtras());
        }
        if (hasSatellites || hasMaxCn0 || hasMeanCn0) {
            if (extras == null) {
                extras = new Bundle();
            }
            if (hasSatellites) extras.putInt("satellites", satellites);
            if (hasMaxCn0) extras.putInt("maxCn0", maxCn0);
            if (hasMeanCn0) extras.putInt("meanCn0", meanCn0);
        }
        if (extras != null) {
            loc.setExtras(extras);
        }
    }

    /**
     * 伪造 GNSS 卫星状态：模拟开启时返回 12 颗卫星（8 GPS + 4 GLONASS，
     * 全部有星历且参与定位解算），让依赖卫星数量的检测逻辑看到"正常定位"；
     * 未开启时返回 null（放行真实状态）。
     *
     * addSatellite 签名随 API 变化（API 24~33 为 8 参数，API 34 起为 12 参数），
     * 用反射兼容。
     */
    public static GnssStatus buildGnssStatus() {
        synchronized (LOCK) {
            if (!enabled || !hasSatellites) {
                return null;
            }
            GnssStatus.Builder b = new GnssStatus.Builder();
            // GPS 1~8 号：信噪比 28~42 dBHz，仰角/方位按序展开
            for (int sv = 1; sv <= 8; sv++) {
                addSatellite(b, sv, GnssStatus.CONSTELLATION_GPS,
                        28f + (sv % 5) * 3.5f,
                        40f + sv * 5f,
                        (sv * 45f) % 360f);
            }
            // GLONASS 65~68 号
            for (int sv = 65; sv <= 68; sv++) {
                addSatellite(b, sv, GnssStatus.CONSTELLATION_GLONASS,
                        24f + (sv % 4) * 2.5f,
                        25f + sv * 7f,
                        (sv * 30f) % 360f);
            }
            return b.build();
        }
    }

    private static final Method ADD_SAT_8 = findAddSatellite(8);
    private static final Method ADD_SAT_12 = findAddSatellite(12);

    private static Method findAddSatellite(int params) {
        for (Method m : GnssStatus.Builder.class.getDeclaredMethods()) {
            if ("addSatellite".equals(m.getName()) && m.getParameterCount() == params) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    private static void addSatellite(GnssStatus.Builder b, int svid, int constellation,
                                     float cn0, float elev, float az) {
        try {
            if (ADD_SAT_12 != null) {
                ADD_SAT_12.invoke(b, svid, constellation, cn0, elev, az,
                        true, true, true, false, 0f, false, 0f);
            } else if (ADD_SAT_8 != null) {
                ADD_SAT_8.invoke(b, svid, constellation, cn0, elev, az,
                        true, true, true);
            }
        } catch (Throwable ignored) {
            // 反射失败则跳过该卫星（尽力而为）
        }
    }
}
