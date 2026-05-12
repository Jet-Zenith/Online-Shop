package com.shop.common;

public class BaseContext {
    private static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

    public static void setCurrentId(String id) {
        THREAD_LOCAL.set(id);
    }

    public static String getCurrentId() {
        return THREAD_LOCAL.get();
    }

    public static void removeCurrentId() {
        THREAD_LOCAL.remove();
    }
}
