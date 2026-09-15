package com.pinloc.app;

/**
 * 反射小工具：跨版本按名查找类（找不到返回 null，不抛异常）。
 * 供各 Hook 类共用，避免重复实现。
 */
final class Reflect {

    private Reflect() {
    }

    /** 按类名查找类；不存在或加载失败返回 null */
    static Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }
}
