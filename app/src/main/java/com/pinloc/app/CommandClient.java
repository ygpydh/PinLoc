package com.pinloc.app;

import android.content.Context;
import android.location.LocationManager;
import android.os.Bundle;

import java.lang.reflect.Method;

/**
 * 模块 App 侧的命令客户端。
 *
 * 通过系统公开 API LocationManager.sendExtraCommand 向框架进程
 * （被注入的 LocationManagerService）下发命令，实现跨进程参数同步。
 *
 * 成功与否以 Hook 回写的 extras.ack 为准，不要把系统 sendExtraCommand
 * 的 boolean 当成「正在模拟」——未注入时系统仍可能返回 true。
 */
public final class CommandClient {

    private CommandClient() {
    }

    private static final Method SEND_EXTRA_CMD = findSendExtraCommand();

    private static Method findSendExtraCommand() {
        try {
            return LocationManager.class.getMethod(
                    "sendExtraCommand", String.class, String.class, Bundle.class);
        } catch (NoSuchMethodException e) {
            try {
                return LocationManager.class.getMethod(
                        "sendExtraCommand", String.class, Bundle.class);
            } catch (NoSuchMethodException e2) {
                return null;
            }
        }
    }

    /**
     * 发送命令。仅当 system_server Hook 回写 extras.ack=true 时返回 true。
     * extras 会被 Hook 原地写入 ack / started（AIDL inout）。
     */
    public static boolean send(Context ctx, Bundle extras) {
        if (ctx == null || extras == null || SEND_EXTRA_CMD == null) {
            android.util.Log.w("PinLoc", "send failed: ctx=" + ctx + " extras=" + extras
                    + " method=" + SEND_EXTRA_CMD);
            return false;
        }
        extras.putBoolean(MockState.KEY_ACK, false);
        extras.putBoolean(MockState.KEY_STARTED, false);
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            android.util.Log.w("PinLoc", "send failed: LocationManager is null");
            return false;
        }
        try {
            if (SEND_EXTRA_CMD.getParameterCount() == 2) {
                SEND_EXTRA_CMD.invoke(lm, MockState.CHANNEL, extras);
            } else {
                SEND_EXTRA_CMD.invoke(lm, ctx.getPackageName(), MockState.CHANNEL, extras);
            }
            boolean ack = extras.getBoolean(MockState.KEY_ACK, false);
            android.util.Log.d("PinLoc", "send action=" + extras.getString("action")
                    + " ack=" + ack + " started=" + extras.getBoolean(MockState.KEY_STARTED, false));
            return ack;
        } catch (Throwable t) {
            android.util.Log.e("PinLoc", "send exception: " + t.getMessage(), t);
            return false;
        }
    }

    public static boolean start(Context ctx, double lat, double lon, Bundle camouflage) {
        Bundle b = camouflage != null ? new Bundle(camouflage) : new Bundle();
        b.putString("action", MockState.ACTION_START);
        b.putDouble(MockState.KEY_LAT, lat);
        b.putDouble(MockState.KEY_LON, lon);
        return send(ctx, b);
    }

    public static boolean putConfig(Context ctx, Bundle camouflage) {
        Bundle b = camouflage != null ? new Bundle(camouflage) : new Bundle();
        b.putString("action", MockState.ACTION_PUT_CONFIG);
        return send(ctx, b);
    }

    public static boolean stop(Context ctx) {
        Bundle b = new Bundle();
        b.putString("action", MockState.ACTION_STOP);
        return send(ctx, b);
    }

    public static boolean move(Context ctx, double lat, double lon) {
        Bundle b = new Bundle();
        b.putString("action", MockState.ACTION_MOVE);
        b.putDouble(MockState.KEY_LAT, lat);
        b.putDouble(MockState.KEY_LON, lon);
        return send(ctx, b);
    }

    /** 查询框架是否正在模拟；Hook 未装上时返回 false。 */
    public static boolean isStarted(Context ctx) {
        Bundle b = new Bundle();
        b.putString("action", MockState.ACTION_IS_START);
        if (!send(ctx, b)) return false;
        return b.getBoolean(MockState.KEY_STARTED, false);
    }
}
