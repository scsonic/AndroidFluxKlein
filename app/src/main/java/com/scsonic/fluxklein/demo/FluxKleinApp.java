package com.scsonic.fluxklein.demo;

import android.app.Application;

public class FluxKleinApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. Sentinel check: if gen_in_progress.txt exists, the previous session crashed
        //    mid-generation (native crash / SIGKILL / OOM in JNI — bypasses Java handler).
        AppLogger.checkGenSentinel(this);
        // 2. Flush any crash written by the Java UncaughtExceptionHandler last session.
        AppLogger.flushCrashPending(this);
        // 3. Register crash handler so future uncaught Java exceptions are persisted.
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        AppLogger.log(this, "APP", "=== App started ===");
    }
}
