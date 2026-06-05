package com.scsonic.fluxklein.demo;

import android.content.Context;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private final Context ctx;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            AppLogger.writeCrashPending(ctx, ex);
        } catch (Throwable ignored) { }
        if (defaultHandler != null) defaultHandler.uncaughtException(thread, ex);
    }
}
