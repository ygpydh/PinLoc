package com.pinloc.app;

import android.location.GnssStatus;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * system_server 进程 Hook（libxposed API 102 版）。
 *
 * 定位模拟 + 命令通道 + 回调拦截 + GNSS 卫星伪造 + WiFi 扫描伪造：
 *   - getLastLocation / getCurrentLocation / getLastKnownLocation 重载
 *     命中即返回模拟位置（未启用时 chain.proceed() 放行真实定位）
 *   - sendExtraCommand(String, String, Bundle) 拦截模块命令通道
 *   - 内部类回调（参数含 Location）替换为模拟位置，覆盖 requestLocationUpdates
 *   - 参数含 GnssStatus 的回调替换为伪造的 12 颗卫星状态
 *   - WifiService / WifiScanningServiceImpl.getScanResults 返回空列表
 *
 * 全部 hook 使用 PROTECTIVE 异常模式：hook 自身出错时按"无此 hook"处理，
 * 不会导致系统进程崩溃。
 */
public final class SystemServerHook {

    private static final String TAG = "PinLoc";
    private static final String LMS_CLASS = "com.android.server.location.LocationManagerService";
    private static final String WIFI_SERVICE = "com.android.server.wifi.WifiService";
    private static final String WIFI_SCAN_SERVICE = "com.android.server.wifi.WifiScanningServiceImpl";

    private SystemServerHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Class<?> lms = Reflect.findClass(LMS_CLASS, cl);
        if (lms == null) {
            module.log(Log.ERROR, TAG, "LocationManagerService not found, skip");
        } else {
            module.log(Log.INFO, TAG, "LocationManagerService found, installing hooks");
            hookLocationGetters(module, lms);
            LocationHooks.hookLmsUpdatePipeline(module, lms);
            hookCommandChannel(module, lms);
            hookLocationCallbacks(module, lms);
            hookGnssStatus(module, lms);
            LocationHooks.installFramework(module, cl, "system_server");
        }
        hookWifiScan(module, cl);
    }

    /** getLastLocation / getCurrentLocation / getLastKnownLocation → 模拟位置 */
    private static void hookLocationGetters(XposedModule module, Class<?> lms) {
        int hooked = 0;
        for (Method m : lms.getDeclaredMethods()) {
            String name = m.getName();
            // getCurrentLocation 由 LocationHooks 兼顾 void/Location 重载，这里只拦 last*
            boolean isGetter = "getLastLocation".equals(name)
                    || "getLastKnownLocation".equals(name);
            if (!isGetter || !Location.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Location real = result instanceof Location ? (Location) result : null;
                        Location mock = MockState.overlay(real);
                        return mock != null ? mock : result;
                    });
            hooked++;
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " location getter(s)");
    }

    /** sendExtraCommand → 拦截模块命令通道（支持两参/三参/四参签名） */
    private static void hookCommandChannel(XposedModule module, Class<?> lms) {
        int hooked = 0;
        for (Method m : lms.getDeclaredMethods()) {
            if (!"sendExtraCommand".equals(m.getName())) {
                continue;
            }
            Class<?>[] pt = m.getParameterTypes();
            int cmdIdx;
            int extrasIdx;
            if (pt.length == 4 && Bundle.class.isAssignableFrom(pt[3])) {
                // (packageName, provider, command, extras) —— Android 高版本
                cmdIdx = 2;
                extrasIdx = 3;
            } else if (pt.length == 3 && Bundle.class.isAssignableFrom(pt[2])) {
                // (provider, command, extras) —— API 34+
                cmdIdx = 1;
                extrasIdx = 2;
            } else if (pt.length == 2 && Bundle.class.isAssignableFrom(pt[1])) {
                // (command, extras) —— API 26~33
                cmdIdx = 0;
                extrasIdx = 1;
            } else {
                continue;
            }
            final int cIdx = cmdIdx;
            final int eIdx = extrasIdx;
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object cmd = chain.getArg(cIdx);
                        if (cmd instanceof String && MockState.CHANNEL.equals(cmd)) {
                            Object extras = chain.getArg(eIdx);
                            boolean handled = MockState.handleCommand(
                                    extras instanceof Bundle ? (Bundle) extras : null);
                            module.log(Log.INFO, TAG,
                                    "command handled=" + handled + " (" + pt.length + "p)");
                            return handled;
                        }
                        return chain.proceed();
                    });
            hooked++;
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " sendExtraCommand(s)");
    }

    /** LMS 内部类 + 任意带 Location/List 参数的回调，覆盖 requestLocationUpdates */
    private static void hookLocationCallbacks(XposedModule module, Class<?> lms) {
        int hooked = LocationHooks.hookLocationArgsOn(module, lms);
        Class<?>[] inners;
        try {
            inners = lms.getDeclaredClasses();
        } catch (Throwable t) {
            inners = new Class<?>[0];
        }
        for (Class<?> inner : inners) {
            hooked += LocationHooks.hookLocationArgsOn(module, inner);
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " location callback(s)");
    }

    /**
     * GNSS 卫星状态伪造：替换参数含 GnssStatus 的回调方法参数。
     * 覆盖新版系统的 GnssStatus 上报路径（LocationManagerService 内部类）。
     */
    private static void hookGnssStatus(XposedModule module, Class<?> lms) {
        int hooked = 0;
        for (Class<?> inner : lms.getDeclaredClasses()) {
            for (Method m : inner.getDeclaredMethods()) {
                boolean hasGnssParam = false;
                for (Class<?> pt : m.getParameterTypes()) {
                    if (GnssStatus.class.isAssignableFrom(pt)) {
                        hasGnssParam = true;
                        break;
                    }
                }
                if (!hasGnssParam) {
                    continue;
                }
                module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            GnssStatus mock = MockState.buildGnssStatus();
                            if (mock == null) {
                                return chain.proceed();
                            }
                            Object[] args = chain.getArgs().toArray();
                            for (int i = 0; i < args.length; i++) {
                                if (args[i] instanceof GnssStatus) {
                                    args[i] = mock;
                                }
                            }
                            return chain.proceed(args);
                        });
                hooked++;
            }
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " gnss status callback(s)");
    }

    /**
     * WiFi 扫描伪造：WifiService / WifiScanningServiceImpl.getScanResults
     * 模拟开启时返回空列表（应用拿不到 WiFi，只能信 GPS 模拟值）。
     */
    private static void hookWifiScan(XposedModule module, ClassLoader cl) {
        for (String clsName : new String[]{WIFI_SERVICE, WIFI_SCAN_SERVICE}) {
            Class<?> clazz = Reflect.findClass(clsName, cl);
            if (clazz == null) {
                continue;
            }
            int hooked = 0;
            for (Method m : clazz.getDeclaredMethods()) {
                if (!"getScanResults".equals(m.getName())
                        || !List.class.isAssignableFrom(m.getReturnType())) {
                    continue;
                }
                module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            if (!MockState.isEnabled()) {
                                return chain.proceed();
                            }
                            return Collections.emptyList();
                        });
                hooked++;
            }
            if (hooked > 0) {
                module.log(Log.INFO, TAG, "hooked " + hooked + "x getScanResults in " + clsName);
            }
        }
    }
}
