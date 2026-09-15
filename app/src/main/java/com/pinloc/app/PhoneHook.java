package com.pinloc.app;

import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 电话/基站进程（com.android.phone）Hook（libxposed API 102 版）。
 *
 * 目的：防止依赖"真实基站信息 / 服务状态"的应用或检测逻辑暴露真实环境，
 * 保证模拟位置是应用唯一可用的位置源：
 *   - getAllCellInfo(*)  → 空列表
 *   - getCellLocation(*) → null
 *   - getServiceState(*) → 正常服务状态
 *   - getNetworkType(*)  → LTE
 *
 * 目标类按名称反射查找（跨版本候选），方法按名称反射枚举 hook，
 * 匹配不到的版本静默跳过；全部 PROTECTIVE 模式，无崩溃风险。
 */
public final class PhoneHook {

    private static final String TAG = "PinLoc";

    /** ITelephony 实现类的跨版本候选 */
    private static final String[] TARGET_CLASSES = {
            "com.android.internal.telephony.PhoneInterfaceManager",
            "com.android.internal.telephony.TelephonyInterfaceManager",
            "com.android.internal.telephony.ITelephony$Stub"
    };

    private PhoneHook() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        module.log(Log.INFO, TAG, "PhoneHook installing");
        for (String clsName : TARGET_CLASSES) {
            Class<?> clazz = Reflect.findClass(clsName, cl);
            if (clazz == null) {
                continue;
            }
            module.log(Log.INFO, TAG, "found telephony class: " + clsName);
            hookAllCellInfo(module, clazz);
            hookCellLocation(module, clazz);
            hookServiceState(module, clazz);
            hookNetworkType(module, clazz);
            return;
        }
        module.log(Log.WARN, TAG, "no telephony interface class found, skip");
    }

    /** getAllCellInfo / getAllCellInfo(boolean) → 空列表（应用拿不到基站） */
    private static void hookAllCellInfo(XposedModule module, Class<?> clazz) {
        hookMethodsByName(module, clazz, "getAllCellInfo", chain -> {
            if (!MockState.isEnabled()) {
                return chain.proceed();
            }
            return Collections.emptyList();
        });
    }

    /** getCellLocation / getCellLocation(String, ...) → null */
    private static void hookCellLocation(XposedModule module, Class<?> clazz) {
        hookMethodsByName(module, clazz, "getCellLocation", chain -> {
            if (!MockState.isEnabled()) {
                return chain.proceed();
            }
            return null;
        });
    }

    /** getServiceState 系列 → 正常服务状态 */
    private static void hookServiceState(XposedModule module, Class<?> clazz) {
        XposedInterface.Hooker h = chain -> {
            if (!MockState.isEnabled()) {
                return chain.proceed();
            }
            return buildInServiceState();
        };
        hookMethodsByName(module, clazz, "getServiceState", h);
        hookMethodsByName(module, clazz, "getServiceStateForSubscriber", h);
        hookMethodsByName(module, clazz, "getServiceStateUsingSubId", h);
    }

    /** getNetworkType 系列 → LTE */
    private static void hookNetworkType(XposedModule module, Class<?> clazz) {
        XposedInterface.Hooker h = chain -> {
            if (!MockState.isEnabled()) {
                return chain.proceed();
            }
            return TelephonyManager.NETWORK_TYPE_LTE;
        };
        hookMethodsByName(module, clazz, "getNetworkType", h);
        hookMethodsByName(module, clazz, "getNetworkTypeForSubscriber", h);
        hookMethodsByName(module, clazz, "getDataNetworkType", h);
        hookMethodsByName(module, clazz, "getDataNetworkTypeForSubscriber", h);
        hookMethodsByName(module, clazz, "getVoiceNetworkType", h);
        hookMethodsByName(module, clazz, "getVoiceNetworkTypeForSubscriber", h);
    }

    private static void hookMethodsByName(XposedModule module, Class<?> clazz,
                                          String name, XposedInterface.Hooker hooker) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            try {
                module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(hooker);
                hooked++;
            } catch (Throwable t) {
                module.log(Log.WARN, TAG, "hook " + name + " failed: " + t);
            }
        }
        if (hooked > 0) {
            module.log(Log.INFO, TAG, "hooked " + hooked + "x " + name);
        }
    }

    /** ServiceState.setState 反射句柄（隐藏 API，缓存避免每次查找） */
    private static final Method SS_SET_STATE = findSetState();

    private static Method findSetState() {
        try {
            return ServiceState.class.getMethod("setState", int.class);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 构造"服务正常"的 ServiceState（setState 为隐藏 API，反射调用） */
    private static ServiceState buildInServiceState() {
        ServiceState ss = new ServiceState();
        try {
            // ServiceState.STATE_IN_SERVICE == 0
            if (SS_SET_STATE != null) {
                SS_SET_STATE.invoke(ss, 0);
            }
        } catch (Throwable t) {
            // 反射失败则使用默认构造（尽力而为）
        }
        return ss;
    }
}
