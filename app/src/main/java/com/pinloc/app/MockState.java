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
    /** 卫星伪装总开关（布尔）：开=GnssSpoof 造 24 颗多星座，关=放行真实 GnssStatus */
    public static final String KEY_SATELLITE_SPOOF = "satellite_spoof";
    /** 定位伪装抖动开关（布尔）：开=OU 物理漂移 ±4m，关=静态钉点 */
    public static final String KEY_LOCATION_JITTER = "location_jitter";
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

    /** 目标坐标（WGS-84） */
    private static double lat = 39.9042;        // 默认：北京
    private static double lon = 116.4074;
    /** 卫星伪装总开关（默认开）。开→GnssSpoof 造 24 颗多星座；关→放行真实 GnssStatus/NMEA。 */
    private static boolean satelliteSpoofEnabled = true;
    /** 定位抖动开关（默认开）。开→OU 物理漂移 ±4m；关→静态钉点。 */
    private static boolean locationJitterEnabled = true;
    /** GnssSpoof 默认造几颗多星座卫星 */
    private static final int DEFAULT_SPOOF_SATELLITES = 24;
    /** OU 抖动开时自动写的 Location 精度（米），和 ±4m 漂移自洽 */
    private static final float DEFAULT_ACCURACY = 5.0f;

    /** 已推进坐标与上次推进时间 */
    private static double curLat = lat;
    private static double curLon = lon;
    private static long lastMoveTime = 0L;

    /**
     * Ornstein-Uhlenbeck 物理抖动状态（米，相对目标点）。
     * 均值回归系数 alpha=0.05/s，稳态 1σ≈1.33m（3σ≈4m 硬限）。
     * 每 1 秒最多走一步，高频回调复用同一步结果。
     */
    private static double jitterNorthM = 0.0;
    private static double jitterEastM = 0.0;
    private static long lastJitterStepMs = 0L;
    private static final double OU_ALPHA = 0.05;
    private static final double OU_SIGMA = 0.42;       // m/√s → 稳态 σ≈1.33m
    private static final double OU_MAX_OFFSET_M = 4.0; // 3σ 硬限
    private static final long OU_STEP_MS = 1000L;
    private static final java.util.Random OU_RAND = new java.util.Random();

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
            return 0f;
        }
    }

    public static double altitude() {
        synchronized (LOCK) {
            return 0.0;
        }
    }

    public static float accuracy() {
        synchronized (LOCK) {
            return DEFAULT_ACCURACY;
        }
    }

    public static float bearing() {
        synchronized (LOCK) {
            return 0f;
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

    /** 调用方须持有 LOCK。只处理两个布尔开关。 */
    private static boolean applyCamouflageLocked(Bundle extras) {
        boolean any = false;
        if (extras.containsKey(KEY_SATELLITE_SPOOF)) {
            satelliteSpoofEnabled = extras.getBoolean(KEY_SATELLITE_SPOOF);
            any = true;
        }
        if (extras.containsKey(KEY_LOCATION_JITTER)) {
            locationJitterEnabled = extras.getBoolean(KEY_LOCATION_JITTER);
            any = true;
        }
        return any;
    }

    private static boolean handleSetBearing(Bundle extras) {
        synchronized (LOCK) {
            return applyCamouflageLocked(extras);
        }
    }

    /** 应用新坐标（UI 选点后调用） */
    public static void apply(double newLat, double newLon) {
        synchronized (LOCK) {
            lat = newLat;
            lon = newLon;
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

            // OU 抖动（开关关时钉死在选点）
            double outLat = curLat;
            double outLon = curLon;
            if (locationJitterEnabled) {
                stepJitterLocked(now);
                outLat = curLat + jitterNorthM / 111320.0;
                outLon = curLon + jitterEastM / (111320.0 * Math.max(0.01, Math.cos(Math.toRadians(curLat))));
            }

            Location loc = real != null ? new Location(real) : new Location("gps");
            loc.setProvider("gps");
            loc.setLatitude(outLat);
            loc.setLongitude(outLon);
            loc.setTime(now);
            loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

            // 海拔跟真实（不硬写）
            // 精度自动 5m（和 OU ±4m 漂移自洽）
            loc.setAccuracy(DEFAULT_ACCURACY);
            // 静止点速度 0
            loc.setSpeed(0.0f);
            // 方位跟真实（不硬写）

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

    /**
     * OU 过程走一步：dX = -α·X·dt + σ·√dt·N(0,1)，3σ 硬限 4m。
     * 调用方须持有 LOCK。每 1 秒最多走一步，高频回调复用。
     */
    private static void stepJitterLocked(long nowMs) {
        if (nowMs - lastJitterStepMs < OU_STEP_MS) {
            return;
        }
        double dt = (nowMs - lastJitterStepMs) / 1000.0;
        if (lastJitterStepMs == 0L) {
            // 首次：从零开始
            lastJitterStepMs = nowMs;
            return;
        }
        if (dt <= 0.0 || dt > 10.0) {
            // 休眠/挂起后复位，避免坐标跳变
            jitterNorthM = 0.0;
            jitterEastM = 0.0;
            lastJitterStepMs = nowMs;
            return;
        }
        double reversionN = -OU_ALPHA * jitterNorthM * dt;
        double reversionE = -OU_ALPHA * jitterEastM * dt;
        double noiseN = OU_SIGMA * Math.sqrt(dt) * OU_RAND.nextGaussian();
        double noiseE = OU_SIGMA * Math.sqrt(dt) * OU_RAND.nextGaussian();
        jitterNorthM += reversionN + noiseN;
        jitterEastM += reversionE + noiseE;
        // 硬限
        double off = Math.sqrt(jitterNorthM * jitterNorthM + jitterEastM * jitterEastM);
        if (off > OU_MAX_OFFSET_M) {
            double k = OU_MAX_OFFSET_M / off;
            jitterNorthM *= k;
            jitterEastM *= k;
        }
        lastJitterStepMs = nowMs;
    }

    /** 当前输出纬度（含 OU 抖动），供 NMEA 拼装用。调用方需自行保证 enabled。 */
    public static double outputLat() {
        synchronized (LOCK) {
            return curLat + jitterNorthM / 111320.0;
        }
    }

    /** 当前输出经度（含 OU 抖动），供 NMEA 拼装用。 */
    public static double outputLon() {
        synchronized (LOCK) {
            return curLon + jitterEastM / (111320.0 * Math.max(0.01, Math.cos(Math.toRadians(curLat))));
        }
    }

    /**
     * NMEA GGA 里的在用卫星数。卫星伪装关时返回 -1（不改写 NMEA 卫星数字段）。
     */
    public static int usedSatellites() {
        synchronized (LOCK) {
            return satelliteSpoofEnabled ? DEFAULT_SPOOF_SATELLITES : -1;
        }
    }

    private static void applyOptionalAccuracies(Location loc, Location real) {
        // speed=0，speed accuracy 给个小值
        try {
            loc.setSpeedAccuracyMetersPerSecond(0.5f);
        } catch (Throwable ignored) {}
        // bearing 跟真实
        if (real != null) {
            try {
                if (real.hasBearingAccuracy()) {
                    loc.setBearingAccuracyDegrees(real.getBearingAccuracyDegrees());
                }
            } catch (Throwable ignored) {}
        }
        // vertical accuracy 跟水平精度一致
        try {
            loc.setVerticalAccuracyMeters(DEFAULT_ACCURACY);
        } catch (Throwable ignored) {}
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
        // 卫星 extras（satellites/maxCn0/meanCn0）不再手写死值：
        // GnssStatus 由 GnssSpoof 实时造，Location extras 透传真实值即可，避免交叉校验不一致。
        if (real != null && real.getExtras() != null) {
            loc.setExtras(new Bundle(real.getExtras()));
        }
    }

    /**
     * 伪造 GNSS 卫星状态。开关 satelliteSpoofEnabled 开 → GnssSpoof 造 24 颗多星座；
     * 关 → 返回 null（放行真实 GnssStatus）。
     */
    public static GnssStatus buildGnssStatus() {
        synchronized (LOCK) {
            if (!enabled || !satelliteSpoofEnabled) {
                return null;
            }
            Object obj = GnssSpoof.getOrCreateGnssStatus(
                    MockState.class.getClassLoader(), DEFAULT_SPOOF_SATELLITES, null);
            return obj instanceof GnssStatus ? (GnssStatus) obj : null;
        }
    }
}
