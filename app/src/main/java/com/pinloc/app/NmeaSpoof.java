package com.pinloc.app;

import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * NMEA 语句伪装（system_server 层）。
 *
 * 原理：NMEA 字符串由 system_server 通过 IOnNmeaMessageListener binder push 给客户端。
 * 我们在 system_server 里 hook {@code IOnNmeaMessageListener$Stub$Proxy.onNmeaReceived}，
 * 把 push 出去的 GGA/RMC 语句里的经纬度、定位质量、卫星数改写成假点对应值。
 *
 * 改写策略：
 *   - $GPGGA：替换纬度/经度字段、fix quality=1 (GPS fix)、在用卫星数
 *   - $GPRMC：替换纬度/经度字段、状态=A 有效
 *   - 其他语句（GSA/GSV/VTG）：原样透传，避免破坏卫星 PRN 表一致性
 *   - 每条改写后重算 NMEA 校验和（$..*hh 之间异或）
 *
 * NMEA 坐标格式：纬度 ddmm.mmmm，经度 dddmm.mmmm。
 */
public final class NmeaSpoof {

    private static final String TAG = "PinLoc";

    private NmeaSpoof() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        // 新版：IOnNmeaMessageListener$Stub$Proxy
        hookProxy(module, cl, "android.location.IOnNmeaMessageListener$Stub$Proxy");
        // 旧版 GpsStatus$NmeaListener 走 binder 时也可能有类似 Stub
        hookProxy(module, cl, "android.location.GpsStatus$NmeaListener");
    }

    private static void hookProxy(XposedModule module, ClassLoader cl, String className) {
        Class<?> clazz = Reflect.findClass(className, cl);
        if (clazz == null) {
            return;
        }
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!"onNmeaReceived".equals(m.getName()) && !"onNmeaMessage".equals(m.getName())) {
                continue;
            }
            int strIdx = -1;
            Class<?>[] pt = m.getParameterTypes();
            for (int i = 0; i < pt.length; i++) {
                if (String.class == pt[i]) {
                    strIdx = i;
                    break;
                }
            }
            if (strIdx < 0) {
                continue;
            }
            final int sIdx = strIdx;
            final int pCount = pt.length;
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!MockState.isEnabled()) {
                            return chain.proceed();
                        }
                        Object[] args = chain.getArgs().toArray();
                        Object orig = args[sIdx];
                        if (orig instanceof String) {
                            args[sIdx] = rewrite((String) orig);
                        }
                        return chain.proceed(args);
                    });
            hooked++;
        }
        if (hooked > 0) {
            module.log(Log.INFO, TAG, "nmea hooked " + hooked + " method(s) in " + className);
        }
    }

    /** 改写一整段 NMEA 文本（可能含多条 $.. 语句，按行处理）。 */
    static String rewrite(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        // 一条 onNmeaReceived 通常只带一条语句，但保险起见按 \n 拆
        String[] lines = raw.split("\r?\n", -1);
        StringBuilder sb = new StringBuilder(raw.length() + 64);
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String out = rewriteLine(line);
            if (out != line && !out.equals(line)) {
                changed = true;
            }
            sb.append(out);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return changed ? sb.toString() : raw;
    }

    /** 改写单条 NMEA 语句。不改写的行原样返回。 */
    private static String rewriteLine(String line) {
        if (line == null || line.isEmpty() || line.charAt(0) != '$') {
            return line;
        }
        int star = line.indexOf('*');
        String body = star > 0 ? line.substring(1, star) : line.substring(1);
        String[] fields = body.split(",", -1);
        if (fields.length == 0) {
            return line;
        }
        String head = fields[0];
        // 兼容 $GPGGA / $GNGGA / $GPGRS 等，取后 3 位判断语句类型
        String type = head.length() >= 5 ? head.substring(head.length() - 3) : head;

        try {
            if ("GGA".equals(type)) {
                return rewriteGga(fields);
            } else if ("RMC".equals(type)) {
                return rewriteRmc(fields);
            }
        } catch (Throwable t) {
            return line;
        }
        return line;
    }

    /** $..GGA: $GPGGA,hhmmss.ss,llll.ll,a,yyyyy.yy,a,quality,numSats,hdop,alt,M,geoid,M,age,stnId*hh */
    private static String rewriteGga(String[] f) {
        // f[0]=GPGGA f[1]=time f[2]=lat f[3]=N/S f[4]=lon f[5]=E/W
        // f[6]=fixQuality f[7]=numSats f[8]=hdop ...
        double lat = MockState.outputLat();
        double lon = MockState.outputLon();
        int sats = MockState.usedSatellites();

        if (f.length > 6) f[6] = "1";                  // GPS fix
        if (f.length > 7 && sats > 0) f[7] = String.valueOf(sats);  // 在用卫星数（用户没填则透传真实值）
        if (f.length > 2) {
            String[] latNmea = toNmeaLat(lat);
            f[2] = latNmea[0];
            f[3] = latNmea[1];
        }
        if (f.length > 4) {
            String[] lonNmea = toNmeaLon(lon);
            f[4] = lonNmea[0];
            f[5] = lonNmea[1];
        }
        return rebuild(f);
    }

    /** $..RMC: $GPRMC,hhmmss.ss,A,llll.ll,a,yyyyy.yy,a,sog,cog,ddmmyy,...*hh */
    private static String rewriteRmc(String[] f) {
        double lat = MockState.outputLat();
        double lon = MockState.outputLon();
        if (f.length > 2) {
            String[] latNmea = toNmeaLat(lat);
            f[2] = latNmea[0];
            f[3] = latNmea[1];
        }
        if (f.length > 4) {
            String[] lonNmea = toNmeaLon(lon);
            f[4] = lonNmea[0];
            f[5] = lonNmea[1];
        }
        if (f.length > 1) f[1] = currentGgaTime();  // 时间也对齐
        return rebuild(f);
    }

    /** 纬度转 NMEA ddmm.mmmm + N/S */
    private static String[] toNmeaLat(double lat) {
        boolean south = lat < 0;
        double abs = Math.abs(lat);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        String coord = String.format(Locale.US, "%02d%07.4f", deg, min);
        return new String[]{coord, south ? "S" : "N"};
    }

    /** 经度转 NMEA dddmm.mmmm + E/W */
    private static String[] toNmeaLon(double lon) {
        boolean west = lon < 0;
        double abs = Math.abs(lon);
        int deg = (int) abs;
        double min = (abs - deg) * 60.0;
        String coord = String.format(Locale.US, "%03d%07.4f", deg, min);
        return new String[]{coord, west ? "W" : "E"};
    }

    /** UTC 时间 hhmmss.ss */
    private static String currentGgaTime() {
        java.util.Calendar c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"));
        return String.format(Locale.US, "%02d%02d%02d.00",
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE),
                c.get(java.util.Calendar.SECOND));
    }

    /** 把 fields 拼回 $..*hh 格式并重算校验和。 */
    private static String rebuild(String[] fields) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) body.append(',');
            body.append(fields[i] == null ? "" : fields[i]);
        }
        int cs = 0;
        for (int i = 0; i < body.length(); i++) {
            cs ^= body.charAt(i);
        }
        return '$' + body.toString() + '*'
                + String.format(Locale.US, "%02X", cs);
    }
}
