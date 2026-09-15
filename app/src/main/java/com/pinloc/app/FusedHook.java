package com.pinloc.app;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 融合定位进程 Hook（AOSP fused / 厂商 fused / GMS）。
 *
 * 对 fused 进程再拦一层 LocationManager 客户端。
 * 部分模拟器 fused 实现是 com.google.android.gms FusedLocationService，
 * 所以对 GMS 类也按 Location 参数做替换。
 */
public final class FusedHook {

    private static final String TAG = "PinLoc";
    private static volatile boolean installed = false;

    private static final String[] CALLBACK_CLASSES = {
            "android.location.IFusedLocationProviderCallback$Stub",
            "com.android.server.location.fused.IFusedLocationProviderCallback$Stub",
            "com.android.server.location.fused.FusedLocationProvider",
            "com.android.server.location.fused.FusedLocationProviderService",
            "com.android.server.location.fused.FusedLocationService",
            "com.google.android.location.fused.FusedLocationService",
            "com.google.android.location.fused.FusedLocationProvider",
            "com.google.android.gms.location.FusedLocationProviderClient",
            "com.google.android.gms.location.internal.FusedLocationProviderResult",
    };

    private FusedHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        if (installed) {
            return;
        }
        installed = true;
        module.log(Log.INFO, TAG, "FusedHook installing");
        LocationHooks.installFramework(module, cl, "fused");
        hookReportCallbacks(module, cl);
        hookAnyLocationMethods(module, cl);
    }

    private static void hookReportCallbacks(XposedModule module, ClassLoader cl) {
        int hooked = 0;
        for (String clsName : CALLBACK_CLASSES) {
            Class<?> clazz = Reflect.findClass(clsName, cl);
            if (clazz == null) {
                continue;
            }
            hooked += LocationHooks.hookLocationArgsOn(module, clazz);
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " fused report callback(s)");
    }

    /** GMS 混淆后类名不稳定：扫描已加载类成本高，改为对已知包前缀再拦 Location 参数方法。 */
    private static void hookAnyLocationMethods(XposedModule module, ClassLoader cl) {
        String[] extra = {
                "com.google.android.location.fused.NlpLocationCollector",
                "com.google.android.gms.internal.location.zzaz",
                "com.google.android.gms.internal.location.zzda",
        };
        int hooked = 0;
        for (String name : extra) {
            Class<?> clazz = Reflect.findClass(name, cl);
            if (clazz != null) {
                hooked += LocationHooks.hookLocationArgsOn(module, clazz);
            }
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " extra fused Location method(s)");
    }
}
