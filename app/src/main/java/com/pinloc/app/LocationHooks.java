package com.pinloc.app;

import android.location.Location;
import android.os.IInterface;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 定位拦截（libxposed API 102）：
 *   - LocationManager 客户端 getter / request*
 *   - LocationListenerTransport / GetCurrentLocationTransport
 *   - requestLocationUpdates 参数里的 IInterface.onLocationChanged
 *   - Location.isFromMockProvider / isMock
 *   - 模拟开启后周期推送假点给已登记监听器
 */
final class LocationHooks {

    private static final String TAG = "PinLoc";

    private static final Set<String> HOOKED_CLASSES = ConcurrentHashMap.newKeySet();
    private static final CopyOnWriteArrayList<Object> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean pusherStarted = false;

    private LocationHooks() {
    }

    static void installFramework(XposedModule module, ClassLoader cl, String where) {
        hookLocationManager(module, cl, where);
        hookTransports(module, cl, where);
        hookAntiDetection(module, cl, where);
        startPusher();
    }

    static void hookLmsUpdatePipeline(XposedModule module, Class<?> lms) {
        int hooked = 0;
        for (Method m : lms.getDeclaredMethods()) {
            String name = m.getName();
            boolean track = "requestLocationUpdates".equals(name)
                    || "registerLocationListener".equals(name)
                    || "requestSingleUpdate".equals(name)
                    || "requestListenerFlush".equals(name)
                    || "getCurrentLocation".equals(name);
            boolean getter = "getLastLocation".equals(name)
                    || "getLastKnownLocation".equals(name)
                    || "getCurrentLocation".equals(name);
            if (!track && !getter) {
                continue;
            }
            final boolean returnLocation = Location.class.isAssignableFrom(m.getReturnType());
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (track) {
                            for (Object arg : chain.getArgs()) {
                                trackListener(module, arg);
                            }
                        }
                        if (returnLocation) {
                            Location real = result instanceof Location ? (Location) result : null;
                            Location mock = MockState.overlay(real);
                            if (mock != null) {
                                return mock;
                            }
                        }
                        return result;
                    });
            hooked++;
        }
        module.log(Log.INFO, TAG, "hooked " + hooked + " LMS update/getter method(s)");
    }

    static int hookLocationArgsOn(XposedModule module, Class<?> clazz) {
        int hooked = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!isLocationCallbackName(m.getName()) || !hasLocationParam(m)) {
                continue;
            }
            try {
                module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> replaceLocationArgs(m, chain));
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        return hooked;
    }

    private static void hookLocationManager(XposedModule module, ClassLoader cl, String where) {
        Class<?> lm = Reflect.findClass("android.location.LocationManager", cl);
        if (lm == null) {
            module.log(Log.WARN, TAG, "LocationManager not found (" + where + ")");
            return;
        }
        int hooked = 0;
        for (Method m : lm.getDeclaredMethods()) {
            String name = m.getName();
            boolean getter = "getLastKnownLocation".equals(name)
                    || "getLastLocation".equals(name)
                    || "getCurrentLocation".equals(name);
            boolean request = "requestLocationUpdates".equals(name)
                    || "requestSingleUpdate".equals(name)
                    || "requestFlush".equals(name);
            if (!getter && !request) {
                continue;
            }
            final boolean returnLocation = Location.class.isAssignableFrom(m.getReturnType());
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (request || "getCurrentLocation".equals(name)) {
                            for (Object arg : chain.getArgs()) {
                                trackListener(module, arg);
                            }
                        }
                        if (returnLocation) {
                            Location real = result instanceof Location ? (Location) result : null;
                            Location mock = MockState.overlay(real);
                            if (mock != null) {
                                return mock;
                            }
                        }
                        return result;
                    });
            hooked++;
        }
        module.log(Log.INFO, TAG, "LocationManager hooked " + hooked + " method(s) (" + where + ")");
    }

    private static void hookTransports(XposedModule module, ClassLoader cl, String where) {
        String[] classes = {
                "android.location.LocationManager$LocationListenerTransport",
                "android.location.LocationManager$GetCurrentLocationTransport",
                "android.location.LocationManager$BatchedLocationCallbackWrapper",
                "android.location.ILocationListener$Stub",
                "android.location.ILocationCallback$Stub",
        };
        int hooked = 0;
        for (String name : classes) {
            Class<?> clazz = Reflect.findClass(name, cl);
            if (clazz == null) {
                continue;
            }
            hooked += hookOnLocationMethods(module, clazz);
        }
        module.log(Log.INFO, TAG, "transport/callback hooked " + hooked + " method(s) (" + where + ")");
    }

    private static void hookAntiDetection(XposedModule module, ClassLoader cl, String where) {
        Class<?> loc = Reflect.findClass("android.location.Location", cl);
        if (loc == null) {
            return;
        }
        int hooked = 0;
        for (Method m : loc.getDeclaredMethods()) {
            String name = m.getName();
            if (!"isFromMockProvider".equals(name) && !"isMock".equals(name)) {
                continue;
            }
            module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> MockState.isEnabled() ? Boolean.FALSE : chain.proceed());
            hooked++;
        }
        module.log(Log.INFO, TAG, "anti-detect hooked " + hooked + " Location mock flag(s) (" + where + ")");
    }

    static void trackListener(XposedModule module, Object obj) {
        if (obj == null) {
            return;
        }
        if (!(obj instanceof IInterface) && !looksLikeLocationCallback(obj.getClass())) {
            return;
        }
        Class<?> cls = obj.getClass();
        hookClassTree(module, cls);
        if (!LISTENERS.contains(obj)) {
            LISTENERS.add(obj);
        }
        if (MockState.isEnabled()) {
            pushTo(obj);
        }
    }

    private static void hookClassTree(XposedModule module, Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            if (HOOKED_CLASSES.add(c.getName())) {
                hookOnLocationMethods(module, c);
            }
            for (Class<?> itf : c.getInterfaces()) {
                if (HOOKED_CLASSES.add(itf.getName())) {
                    hookOnLocationMethods(module, itf);
                }
            }
        }
    }

    private static int hookOnLocationMethods(XposedModule module, Class<?> cls) {
        int hooked = 0;
        Method[] methods;
        try {
            methods = cls.getDeclaredMethods();
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : methods) {
            if (!isLocationCallbackName(m.getName()) || !hasLocationParam(m)) {
                continue;
            }
            try {
                module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> replaceLocationArgs(m, chain));
                hooked++;
            } catch (Throwable ignored) {
            }
        }
        return hooked;
    }

    private static Object replaceLocationArgs(Method m, XposedInterface.Chain chain) throws Throwable {
        if (!MockState.isEnabled()) {
            return chain.proceed();
        }
        Object[] args = chain.getArgs().toArray();
        Class<?>[] pt = m.getParameterTypes();
        boolean replaced = false;
        for (int i = 0; i < args.length && i < pt.length; i++) {
            if (args[i] instanceof Location) {
                Location mock = MockState.overlay((Location) args[i]);
                if (mock != null) {
                    args[i] = mock;
                    replaced = true;
                }
            } else if (args[i] instanceof List && isLocationListParam(pt[i], args[i])) {
                List<?> list = (List<?>) args[i];
                Location real = null;
                for (Object o : list) {
                    if (o instanceof Location) {
                        real = (Location) o;
                        break;
                    }
                }
                Location mock = MockState.overlay(real);
                if (mock != null) {
                    args[i] = Collections.singletonList(mock);
                    replaced = true;
                }
            }
        }
        return replaced ? chain.proceed(args) : chain.proceed();
    }

    static void pushAll() {
        if (!MockState.isEnabled()) {
            return;
        }
        for (Object listener : LISTENERS) {
            pushTo(listener);
        }
    }

    private static void pushTo(Object listener) {
        Location mock = MockState.overlay(null);
        if (mock == null || listener == null) {
            return;
        }
        try {
            Method[] methods = listener.getClass().getMethods();
            for (Method method : methods) {
                if (!isLocationCallbackName(method.getName())) {
                    continue;
                }
                Class<?>[] pt = method.getParameterTypes();
                if (pt.length == 0) {
                    continue;
                }
                if (pt.length != 1) {
                    continue;
                }
                if (Location.class.isAssignableFrom(pt[0])) {
                    method.invoke(listener, mock);
                    return;
                }
                if (isLocationListParam(pt[0], null)) {
                    method.invoke(listener, Collections.singletonList(mock));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void startPusher() {
        if (pusherStarted) {
            return;
        }
        pusherStarted = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    pushAll();
                } catch (Throwable ignored) {
                }
            }
        }, "pinloc-push");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isLocationCallbackName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return n.contains("onlocationchanged")
                || n.equals("onlocation")
                || n.contains("onlocationresult")
                || n.contains("onlocationreported")
                || n.contains("reportlocation");
    }

    private static boolean hasLocationParam(Method m) {
        for (Class<?> pt : m.getParameterTypes()) {
            if (Location.class.isAssignableFrom(pt) || isLocationListParam(pt, null)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLocationListParam(Class<?> type, Object value) {
        if (List.class.isAssignableFrom(type)) {
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                return list.isEmpty() || list.get(0) instanceof Location;
            }
            return true;
        }
        return false;
    }

    private static boolean looksLikeLocationCallback(Class<?> cls) {
        try {
            for (Method m : cls.getMethods()) {
                if (isLocationCallbackName(m.getName()) && hasLocationParam(m)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
}
