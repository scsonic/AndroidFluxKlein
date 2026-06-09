package com.scsonic.fluxklein.demo;

import android.app.Application;

public class FluxKleinApp extends Application {

    /** Non-null if the previous session was force-closed during generation. Consumed by MainActivity. */
    public static volatile String pendingCrashParams = null;

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. Sentinel check: popGenSentinel logs + deletes the file, returns params for dialog.
        pendingCrashParams = AppLogger.popGenSentinel(this);
        // 2. Flush any crash written by the Java UncaughtExceptionHandler last session.
        AppLogger.flushCrashPending(this);
        // 3. Register crash handler so future uncaught Java exceptions are persisted.
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(this));
        AppLogger.log(this, "APP", "=== App started ===");
    }
}
