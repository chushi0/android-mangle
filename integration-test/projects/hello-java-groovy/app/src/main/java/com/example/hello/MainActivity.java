package com.example.hello;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import cc.cszt0.mangle.annotation.Mangle;

public class MainActivity extends Activity {
    static {
        System.loadLibrary("hello");
    }

    private native String nativeGreeting();

    @Mangle
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String greeting = nativeGreeting();
        Log.i("MangleTest", "greeting=" + greeting + " features=" + Features.selfTest());
        TextView view = new TextView(this);
        view.setText(greeting);
        setContentView(view);
    }
}
