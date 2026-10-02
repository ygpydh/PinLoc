package com.pinloc.app;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高性能零分配 GNSS 卫星模拟引擎。
 *
 * 核心策略：
 *   1. 单例反射预热：首次使用时缓存 Method/Field 句柄，高频回调零反射查找。
 *   2. 1Hz 快照缓存：无论系统 GNSS 回调 1Hz 还是 10Hz，1 秒内复用同一对象，零 GC。
 *   3. 多星座模板：BDS(亚太主力) 12 颗 + GPS 12 颗 + GLONASS 4 颗 + Galileo 4 颗。
 *   4. 双路径构建：
 *        - API 30+ (Android 11+)：反射 {@code GnssStatus$Builder.addSatellite(12参)}
 *        - API 26~29：直接在 fallback 原始 GnssStatus 对象上写内部字段
 *        - 旧版 GpsStatus：构造 GpsSatellite 列表
 *   5. C/N0 按仰角加权（34~43.5 dB-Hz），仰角 > 18° 视为 usedInFix。
 *
 * 本类只负责"造一个看起来真的 GnssStatus"，不决定什么时候替换——替换由 SystemServerHook 调。
 */
public final class GnssSpoof {

    private static final String TAG = "PinLoc";
    private static final int MAX_SATELLITES = 32;
    private static final long SNAPSHOT_TTL_MS = 1000L;

    /** 多星座类型：GPS=1, SBAS=2, GLONASS=3, QZSS=4, BDS=5, GAL=6 */
    private static final int[] CONSTELLATION_TYPES = {
            // BDS 12
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            // GPS 12
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            // GLONASS 4
            3, 3, 3, 3,
            // Galileo 4
            6, 6, 6, 6
    };

    private static final int[] SVID_LIST = {
            // BDS SVID 1~14
            1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 13, 14,
            // GPS SVID
            1, 3, 6, 7, 8, 9, 11, 14, 17, 19, 21, 22,
            // GLONASS
            1, 2, 3, 4,
            // Galileo
            1, 2, 3, 5
    };

    private static final float[] BASE_ELEVATIONS = {
            78f, 65f, 54f, 42f, 38f, 72f, 58f, 49f, 33f, 61f, 45f, 29f,
            82f, 69f, 51f, 44f, 36f, 75f, 63f, 47f, 31f, 59f, 41f, 25f,
            55f, 43f, 35f, 28f,
            60f, 48f, 39f, 22f
    };

    private static final float[] BASE_AZIMUTHS = {
            12f, 45f, 78f, 112f, 145f, 178f, 212f, 245f, 278f, 312f, 335f, 25f,
            35f, 68f, 95f, 132f, 165f, 198f, 232f, 265f, 298f, 325f, 15f, 52f,
            85f, 175f, 265f, 345f,
            45f, 135f, 225f, 315f
    };

    /** 平滑微扰动表（循环索引，避免 Random 开销） */
    private static final float[] JITTER_TABLE = {
            -0.8f, 0.5f, 1.2f, -0.3f, 0.9f, -1.1f, 0.4f, -0.6f,
            1.1f, -0.9f, 0.7f, -0.4f, 1.3f, -1.2f, 0.3f, -0.2f
    };

    // ---- 1Hz 缓存 ----
    private static volatile Object cachedStatus;
    private static volatile long lastStatusTimeMs;
    private static volatile int cachedStatusCount = -1;

    private static volatile List<?> cachedSatellites;
    private static volatile long lastSatellitesTimeMs;
    private static volatile int cachedSatellitesCount = -1;

    /** 按 ClassLoader 缓存反射句柄（system_server 只有一个 CL，但保持通用） */
    private static final ConcurrentHashMap<ClassLoader, Holder> HOLDERS = new ConcurrentHashMap<>();

    private GnssSpoof() {
    }

    private static final class Holder {
        final ClassLoader cl;
        volatile boolean initialized;

        // API 30+ GnssStatus$Builder
        Constructor<?> builderCtor;
        Method addSat12;
        Method buildMethod;

        // API 26~29 GnssStatus 内部字段
        Field mSvCount;
        Field mSvidWithFlags;
        Field mCn0DbHz;
        Field mElevations;
        Field mAzimuths;
        Field mCarrierFrequencies;
        Field mBasebandCn0DbHzs;

        // 旧版 GpsSatellite
        Constructor<?> gpsSatCtor;
        Field satValid;
        Field satHasEphemeris;
        Field satHasAlmanac;
        Field satUsedInFix;
        Field satSnr;
        Field satElevation;
        Field satAzimuth;

        Holder(ClassLoader cl) {
            this.cl = cl;
        }

        synchronized void init() {
            if (initialized) return;
            // 1. GnssStatus$Builder (API 30+)
            try {
                Class<?> bc = Class.forName("android.location.GnssStatus$Builder");
                builderCtor = bc.getDeclaredConstructor();
                builderCtor.setAccessible(true);
                addSat12 = bc.getDeclaredMethod("addSatellite",
                        int.class, int.class, float.class, float.class, float.class,
                        boolean.class, boolean.class, boolean.class, boolean.class,
                        float.class, boolean.class, float.class);
                addSat12.setAccessible(true);
                buildMethod = bc.getDeclaredMethod("build");
                buildMethod.setAccessible(true);
            } catch (Throwable ignored) {
            }

            // 2. GnssStatus 内部字段 (API 26~29)
            try {
                Class<?> gs = Class.forName("android.location.GnssStatus");
                mSvCount = findField(gs, "mSvCount");
                mSvidWithFlags = findField(gs, "mSvidWithFlags");
                mCn0DbHz = findField(gs, "mCn0DbHz");
                mElevations = findField(gs, "mElevations");
                mAzimuths = findField(gs, "mAzimuths");
                mCarrierFrequencies = findFieldQuiet(gs, "mCarrierFrequencies");
                mBasebandCn0DbHzs = findFieldQuiet(gs, "mBasebandCn0DbHzs");
            } catch (Throwable ignored) {
            }

            // 3. GpsSatellite
            try {
                Class<?> sat = Class.forName("android.location.GpsSatellite");
                gpsSatCtor = sat.getDeclaredConstructor(int.class);
                gpsSatCtor.setAccessible(true);
                satValid = findFieldQuiet(sat, "mValid");
                satHasEphemeris = findFieldQuiet(sat, "mHasEphemeris");
                satHasAlmanac = findFieldQuiet(sat, "mHasAlmanac");
                satUsedInFix = findFieldQuiet(sat, "mUsedInFix");
                satSnr = findFieldQuiet(sat, "mSnr");
                satElevation = findFieldQuiet(sat, "mElevation");
                satAzimuth = findFieldQuiet(sat, "mAzimuth");
            } catch (Throwable ignored) {
            }

            initialized = true;
        }

        private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        private static Field findFieldQuiet(Class<?> c, String name) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static Holder holder(ClassLoader cl) {
        Holder h = HOLDERS.get(cl);
        if (h == null) {
            h = new Holder(cl);
            Holder prev = HOLDERS.putIfAbsent(cl, h);
            if (prev != null) h = prev;
        }
        if (!h.initialized) h.init();
        return h;
    }

    /**
     * 取或造一个伪造的 GnssStatus。1 秒内重复调用复用同一对象。
     *
     * @param fallbackOriginal 真实回调里带进来的 GnssStatus（API 26~29 字段直写用，可为 null）
     * @param targetCount      期望卫星数（4~32 之间 clamp）
     * @param cl               回调所在 ClassLoader
     * @return 伪造的 GnssStatus；构建彻底失败时返回 fallbackOriginalObj（放行）
     */
    public static Object getOrCreateGnssStatus(ClassLoader cl, int targetCount, Object fallbackOriginal) {
        int count = Math.max(4, Math.min(MAX_SATELLITES, targetCount));
        long now = System.currentTimeMillis();

        Object cached = cachedStatus;
        if (cached != null && cachedStatusCount == count && now - lastStatusTimeMs < SNAPSHOT_TTL_MS) {
            return cached;
        }

        synchronized (GnssSpoof.class) {
            cached = cachedStatus;
            if (cached != null && cachedStatusCount == count && now - lastStatusTimeMs < SNAPSHOT_TTL_MS) {
                return cached;
            }

            Holder h = holder(cl);
            long timeStep = now / 1000L;

            // 路径 A：API 30+ Builder
            if (h.builderCtor != null && h.addSat12 != null && h.buildMethod != null) {
                try {
                    Object builder = h.builderCtor.newInstance();
                    for (int i = 0; i < count; i++) {
                        float elev = elevationAt(i, timeStep);
                        float az = azimuthAt(i, timeStep);
                        float cn0 = cn0At(i, timeStep, elev);
                        boolean usedInFix = elev > 18f;
                        float carrier = carrierFreq(h, i);
                        float baseband = Math.max(26f, cn0 - 2.5f);
                        h.addSat12.invoke(builder,
                                CONSTELLATION_TYPES[i],
                                SVID_LIST[i],
                                cn0, elev, az,
                                true,   // hasEphemeris
                                true,   // hasAlmanac
                                usedInFix,
                                true,   // hasCarrierFrequency
                                carrier,
                                true,   // hasBasebandCn0
                                baseband);
                    }
                    Object built = h.buildMethod.invoke(builder);
                    cachedStatus = built;
                    lastStatusTimeMs = now;
                    cachedStatusCount = count;
                    return built;
                } catch (Throwable t) {
                    Log.w(TAG, "GnssStatus.Builder build failed: " + t);
                }
            }

            // 路径 B：API 26~29 字段直写（改在 fallback 对象上）
            if (fallbackOriginal != null && h.mSvCount != null && h.mSvidWithFlags != null) {
                try {
                    h.mSvCount.setInt(fallbackOriginal, count);
                    float[] cn0s = new float[count];
                    float[] elevs = new float[count];
                    float[] azis = new float[count];
                    int[] svidFlags = new int[count];
                    float[] carriers = new float[count];
                    float[] basebands = new float[count];
                    for (int i = 0; i < count; i++) {
                        float elev = elevationAt(i, timeStep);
                        float az = azimuthAt(i, timeStep);
                        float cn0 = cn0At(i, timeStep, elev);
                        boolean usedInFix = elev > 18f;
                        cn0s[i] = cn0;
                        elevs[i] = elev;
                        azis[i] = az;
                        carriers[i] = carrierFreq(h, i);
                        basebands[i] = cn0 - 2.5f;
                        int flags = 1 | 2; // hasEphemeris | hasAlmanac
                        if (usedInFix) flags |= 4;
                        svidFlags[i] = (SVID_LIST[i] << 8) | (CONSTELLATION_TYPES[i] & 0xF) | (flags << 4);
                    }
                    h.mSvidWithFlags.set(fallbackOriginal, svidFlags);
                    if (h.mCn0DbHz != null) h.mCn0DbHz.set(fallbackOriginal, cn0s);
                    if (h.mElevations != null) h.mElevations.set(fallbackOriginal, elevs);
                    if (h.mAzimuths != null) h.mAzimuths.set(fallbackOriginal, azis);
                    if (h.mCarrierFrequencies != null) h.mCarrierFrequencies.set(fallbackOriginal, carriers);
                    if (h.mBasebandCn0DbHzs != null) h.mBasebandCn0DbHzs.set(fallbackOriginal, basebands);
                    cachedStatus = fallbackOriginal;
                    lastStatusTimeMs = now;
                    cachedStatusCount = count;
                    return fallbackOriginal;
                } catch (Throwable t) {
                    Log.w(TAG, "GnssStatus field write failed: " + t);
                }
            }
        }
        return fallbackOriginal;
    }

    /**
     * 旧版 GpsStatus.getSatellites() 兼容：构造 GpsSatellite 列表。
     */
    public static List<Object> getOrCreateGpsSatellites(ClassLoader cl, int targetCount) {
        int count = Math.max(4, Math.min(MAX_SATELLITES, targetCount));
        long now = System.currentTimeMillis();
        List<?> cached = cachedSatellites;
        if (cached != null && cachedSatellitesCount == count && now - lastSatellitesTimeMs < SNAPSHOT_TTL_MS) {
            //noinspection unchecked
            return (List<Object>) cached;
        }
        synchronized (GnssSpoof.class) {
            cached = cachedSatellites;
            if (cached != null && cachedSatellitesCount == count && now - lastSatellitesTimeMs < SNAPSHOT_TTL_MS) {
                //noinspection unchecked
                return (List<Object>) cached;
            }
            Holder h = holder(cl);
            if (h.gpsSatCtor == null) {
                return new ArrayList<>(0);
            }
            long timeStep = now / 1000L;
            List<Object> list = new ArrayList<>(count);
            try {
                for (int i = 0; i < count; i++) {
                    int type = CONSTELLATION_TYPES[i];
                    int prn = type == 3 ? SVID_LIST[i] + 64 : SVID_LIST[i];
                    Object sat = h.gpsSatCtor.newInstance(prn);
                    float elev = elevationAt(i, timeStep);
                    float az = azimuthAt(i, timeStep);
                    float snr = cn0At(i, timeStep, elev);
                    boolean usedInFix = elev > 18f;
                    setBool(h.satValid, sat, true);
                    setBool(h.satHasEphemeris, sat, true);
                    setBool(h.satHasAlmanac, sat, true);
                    setBool(h.satUsedInFix, sat, usedInFix);
                    setFloat(h.satSnr, sat, snr);
                    setFloat(h.satElevation, sat, elev);
                    setFloat(h.satAzimuth, sat, az);
                    list.add(sat);
                }
                cachedSatellites = list;
                lastSatellitesTimeMs = now;
                cachedSatellitesCount = count;
                return list;
            } catch (Throwable t) {
                Log.w(TAG, "GpsSatellite build failed: " + t);
                return new ArrayList<>(0);
            }
        }
    }

    private static float elevationAt(int i, long timeStep) {
        float drift = ((timeStep + i * 3) % 360);
        float e = BASE_ELEVATIONS[i] + (drift % 10 - 5);
        return clamp(e, 16f, 88f);
    }

    private static float azimuthAt(int i, long timeStep) {
        float drift = ((timeStep + i * 3) % 360);
        float a = BASE_AZIMUTHS[i] + drift * 0.1f;
        return a % 360f;
    }

    private static float cn0At(int i, long timeStep, float elev) {
        float jitter = JITTER_TABLE[(int) ((timeStep + i) & 15)];
        float c = 34f + (elev / 90f) * 8f + jitter;
        return clamp(c, 30f, 43.5f);
    }

    private static float carrierFreq(Holder h, int i) {
        int type = CONSTELLATION_TYPES[i];
        switch (type) {
            case 5: return 1561098000f; // BDS B1I
            case 3: return 1602000000f; // GLONASS L1
            case 6: return 1575420000f; // GAL E1
            default: return 1575420000f; // GPS L1
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void setBool(Field f, Object o, boolean v) {
        if (f != null) try { f.setBoolean(o, v); } catch (Throwable ignored) {}
    }

    private static void setFloat(Field f, Object o, float v) {
        if (f != null) try { f.setFloat(o, v); } catch (Throwable ignored) {}
    }
}
