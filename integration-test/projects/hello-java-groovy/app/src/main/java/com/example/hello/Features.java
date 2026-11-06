package com.example.hello;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import cc.cszt0.mangle.annotation.Mangle;

/** Exercises what moved code loses by leaving its class; returns "ok" or the first failure. */
class Features extends Base implements Greeter {
    private int counter = 40;
    private static String secret = "s3";

    private int bump(int by) {
        counter += by;
        return counter;
    }

    private static class Hidden {
        private final String value;

        private Hidden(String value) {
            this.value = value;
        }
    }

    @Mangle
    private int privateMembers() {
        secret = secret + "!";
        return bump(2);
    }

    @Mangle
    String superCall() {
        return super.describe() + "+protected:" + protectedField + protectedMethod();
    }

    @Override
    String describe() {
        return "sub";
    }

    @Mangle
    @Override
    public String greet() {
        return Greeter.super.greet() + "!";
    }

    @Mangle
    String lambdas(String prefix) {
        Supplier<Integer> capturesThis = () -> counter + prefix.length();
        Function<String, Hidden> constructorRef = Hidden::new;
        List<String> list = Arrays.asList("b", "a");
        list.sort(String::compareTo);
        return constructorRef.apply(list.get(0)).value + capturesThis.get();
    }

    @Mangle
    Features self(Features other, Features[] many, long wide, double wider) {
        return wide + wider > 0 ? many[0] : other;
    }

    @Mangle
    synchronized boolean locked() {
        return Thread.holdsLock(this);
    }

    @Mangle
    int branches(int n) {
        int total = 0;
        for (int i = 0; i < n; i++) {
            try {
                if (i % 2 == 0) {
                    throw new IllegalStateException();
                }
                total += i;
            } catch (IllegalStateException e) {
                total -= 1;
            } finally {
                total += 10;
            }
        }
        return total;
    }

    @Mangle
    static String overloaded(int value) {
        return "int" + value;
    }

    @Mangle
    static String overloaded(String value) {
        return "string" + value;
    }

    static String selfTest() {
        Features f = new Features();
        if (f.privateMembers() != 42 || !secret.equals("s3!")) return "privateMembers";
        if (!f.superCall().equals("base+protected:7p")) return "superCall:" + f.superCall();
        if (!f.greet().equals("hi!")) return "greet";
        if (!f.lambdas("xy").equals("a44")) return "lambdas:" + f.lambdas("xy");
        Features other = new Features();
        if (f.self(other, new Features[] {f}, 1, 1) != f) return "self";
        if (!f.locked()) return "locked";
        if (f.branches(4) != 42) return "branches:" + f.branches(4);
        if (!overloaded(1).equals("int1") || !overloaded("a").equals("stringa")) return "overloaded";
        if (!GreetingBridge.nativeThrowIsCaught()) return "nativeThrow";
        return "ok";
    }
}

class Base {
    protected int protectedField = 7;

    protected String protectedMethod() {
        return "p";
    }

    String describe() {
        return "base";
    }
}

interface Greeter {
    default String greet() {
        return "hi";
    }
}
