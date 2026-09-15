package com.pinloc.app;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 模块入口。
 *
 * 入口类通过 META-INF/xposed/java_init.list 声明，作用域见
 * META-INF/xposed/scope.list：
 *   system（system_server）、com.android.phone（电话）、
 *   com.android.bluetooth（蓝牙）、
 *   com.android.location.fused / 厂商 fused / com.google.android.gms（GMS fused）。
 *
 * 进程分发：
 *   - system_server（虚拟包名 system）→ onSystemServerStarting → SystemServerHook
 *   - 电话进程 com.android.phone        → onPackageReady → PhoneHook
 *   - 蓝牙进程 com.android.bluetooth    → onPackageReady → BluetoothHook
 *   - 厂商融合定位进程                  → onPackageReady → FusedHook
 *   - 其余进程                          → 直接 detach，不接收后续回调
 */
public final class PinLocModule extends XposedModule {

    private static final String TAG = "PinLoc";
    private static final String PKG_PHONE = "com.android.phone";
    private static final String PKG_BLUETOOTH = "com.android.bluetooth";

    /** 厂商融合定位 + GMS fused */
    private static final String[] FUSED_PKGS = {
            "com.android.location.fused",
            "com.xiaomi.location.fused",
            "com.oplus.location",
            "com.huawei.location.lite",
            "com.huawei.location",
            "com.vivo.location",
            "com.hihonor.location",
            "com.google.android.gms"
    };

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "module loaded, process=" + param.getProcessName()
                + ", framework=" + getFrameworkName()
                + " (" + getFrameworkVersion() + "), API " + getApiVersion());
    }

    /** system_server 启动时：安装定位模拟 / 命令通道 / 回调拦截 / GNSS / WiFi */
    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        log(Log.INFO, TAG, "system server starting, installing location hooks");
        // system_server 是命令权威进程：开关以本地状态为真源，不读系统属性
        MockState.setAuthorityProcess(true);
        try {
            SystemServerHook.install(this, param.getClassLoader());
            // hook 安装成功，写入模块激活标记（App 侧通过读此属性判断模块是否激活）
            MockState.markModuleActive();
            log(Log.INFO, TAG, "system server hooks installed, module marked active");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "system server hook failed", t);
        }
        // system_server 不会再有其他包加载，任务完成后脱离
        detach();
    }

    /** 应用进程：按包名分发（电话 / 蓝牙 / 融合定位），其余进程立即脱离 */
    @Override
    public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        // 子进程：开关以系统属性为跨进程真源（单向读）
        MockState.setAuthorityProcess(false);
        if (PKG_PHONE.equals(pkg)) {
            log(Log.INFO, TAG, "phone process ready, installing anti-detection hooks");
            try {
                PhoneHook.install(this, param.getClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "phone hook failed", t);
            }
        } else if (PKG_BLUETOOTH.equals(pkg)) {
            log(Log.INFO, TAG, "bluetooth process ready, installing hooks");
            try {
                BluetoothHook.install(this, param.getClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "bluetooth hook failed", t);
            }
        } else if (isFusedPkg(pkg)) {
            log(Log.INFO, TAG, "fused process ready (" + pkg + "), installing hooks");
            try {
                FusedHook.install(this, param.getClassLoader());
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "fused hook failed", t);
            }
            // GMS 进程会加载多个包，不能立刻 detach，否则后续 fused 类装不上
            if (!"com.google.android.gms".equals(pkg)) {
                detach();
            }
            return;
        }
        detach();
    }

    private static boolean isFusedPkg(String pkg) {
        for (String p : FUSED_PKGS) {
            if (p.equals(pkg)) {
                return true;
            }
        }
        return false;
    }
}
