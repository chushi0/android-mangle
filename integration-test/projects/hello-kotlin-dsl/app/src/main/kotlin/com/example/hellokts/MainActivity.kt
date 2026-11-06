package com.example.hellokts

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import cc.cszt0.mangle.MangleIdentifiers

class MainActivity : Activity() {
    // Kotlin is not scanned, so this stays where it is and binds by its original JNI name.
    private external fun kotlinNative(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("hellokts")
        // Kotlin compiles before javac, which only works if the constants exist by then.
        val holder = Class.forName(MangleIdentifiers.COM_EXAMPLE_HELLOKTS_NATIVEGREETING)
        val greeting = holder.getMethod(MangleIdentifiers.COM_EXAMPLE_HELLOKTS_NATIVEGREETING_FETCH).invoke(null)
        Log.i("MangleTest", "greeting=$greeting kotlinNative=${kotlinNative()}")
        setContentView(TextView(this).apply { text = greeting.toString() })
    }
}
