package com.example.hellokts;

import cc.cszt0.mangle.annotation.Mangle;

class NativeGreeting {
    static native String fetch();

    @Mangle
    public static String fromNative() {
        return "Hello world";
    }
}
