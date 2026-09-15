package com.pinloc.app;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 蓝牙进程（com.android.bluetooth）Hook（libxposed API 102 版）。
 *
 * 目的：与应用拿不到 WiFi / 基站同理——模拟开启时让应用拿不到任何
 * 蓝牙设备信息（bonded / connected），无法用蓝牙 beacon 做定位或
 * 暴露真实蓝牙环境；蓝牙开关等真实功能不受影响（不 hook 状态类方法）。
 *
 * 目标：AdapterService（bonded/connected）、各 profile 服务
 * （A2DP / HFP / HID / PAN / GATT）的 getConnectedDevices 系列。
 * 返回类型为 Set 时返回空 Set，List 时返回空 List（避免 Binder
 * 反序列化类型不匹配）。
 *
 * 全部 PROTECTIVE 模式 + 未启用时放行，无崩溃风险。
 */
public final class BluetoothHook {

    private static final String TAG = "PinLoc";

    /** 蓝牙服务类的跨版本候选 */
    private static final String[] TARGET_CLASSES = {
            "com.android.bluetooth.btservice.AdapterService",
            "com.android.server.bluetooth.BluetoothManagerService",
            "com.android.bluetooth.a2dp.A2dpService",
            "com.android.bluetooth.hfp.HeadsetService",
            "com.android.bluetooth.hid.HidService",
            "com.android.bluetooth.pan.PanService",
            "com.android.bluetooth.gatt.GattService",
            "com.android.bluetooth.hfpclient.HeadsetClientService",
            "com.android.bluetooth.map.BluetoothMapService",
    };

    /** 要清空的设备查询方法 */
    private static final String[] TARGET_METHODS = {
            "getBondedDevices",
            "getConnectedDevices",
            "getProfileConnectedDevices",
    };

    private BluetoothHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        module.log(Log.INFO, TAG, "BluetoothHook installing");
        int total = 0;
        for (String clsName : TARGET_CLASSES) {
            Class<?> clazz = Reflect.findClass(clsName, cl);
            if (clazz == null) {
                continue;
            }
            int hooked = hookDeviceMethods(module, clazz);
            if (hooked > 0) {
                module.log(Log.INFO, TAG, "hooked " + hooked + "x device method(s) in " + clsName);
                total += hooked;
            }
        }
        module.log(Log.INFO, TAG, "BluetoothHook done, total hooked=" + total);
    }

    private static int hookDeviceMethods(XposedModule module, Class<?> clazz) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!isTargetMethod(m.getName())) {
                continue;
            }
            if (!Collection.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            final boolean isSet = Set.class.isAssignableFrom(m.getReturnType());
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!MockState.isEnabled()) {
                            return chain.proceed();
                        }
                        // 返回类型 Set → 空 Set，List → 空 List（Binder 反序列化类型需一致）
                        return isSet ? Collections.emptySet() : Collections.emptyList();
                    });
            hooked++;
        }
        return hooked;
    }

    private static boolean isTargetMethod(String name) {
        for (String t : TARGET_METHODS) {
            if (t.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
