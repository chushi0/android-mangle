package com.example.hello;

import java.lang.reflect.Method;

import cc.cszt0.mangle.MangleIdentifiers;
import cc.cszt0.mangle.annotation.Mangle;

/** Called from JNI; reaches the greeting through reflection on generated identifiers. */
class GreetingBridge {
    @Mangle
    static String readGreeting() throws ReflectiveOperationException {
        Class<?> source = Class.forName(MangleIdentifiers.COM_EXAMPLE_HELLO_GREETINGSOURCE);
        Method method = source.getMethod(MangleIdentifiers.COM_EXAMPLE_HELLO_GREETINGSOURCE_LOADGREETING);
        return (String) method.invoke(null);
    }
}
