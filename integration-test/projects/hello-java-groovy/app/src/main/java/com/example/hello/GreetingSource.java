package com.example.hello;

import cc.cszt0.mangle.annotation.Mangle;

@Mangle
final class GreetingSource {
    private static final StringBuilder PARTS = new StringBuilder();

    static String loadGreeting() {
        PARTS.setLength(0);
        return PARTS.append("Hello").append(' ').append("world").toString();
    }
}
