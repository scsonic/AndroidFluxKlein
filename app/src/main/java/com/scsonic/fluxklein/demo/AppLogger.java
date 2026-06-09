package com.scsonic.fluxklein.demo;

import android.content.Context;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AppLogger {

    private static final String LOG_FILE      = "app_log.txt";
    private static final String CRASH_FILE    = "crash_pending.txt";
    private static final String SENTINEL_FILE = "gen_in_progress.txt";
    private static final int    MAX_LINES     = 1000;

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    // ── Public API ────────────────────────────────────────────────────────────

    public static synchronized void log(Context ctx, String tag, String msg) {
        appendLine(ctx, LOG_FILE, ts() + " [" + tag + "] " + msg);
    }

    public static synchronized void clearLog(Context ctx) {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public static synchronized String readAll(Context ctx) {
        File f = new File(ctx.getFilesDir(), LOG_FILE);
        if (!f.exists()) return "(no log yet)";
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.length() > 0 ? sb.toString() : "(log is empty)";
        } catch (IOException e) {
            return "(read error: " + e.getMessage() + ")";
        }
    }

    /**
     * Called from UncaughtExceptionHandler — writes crash to a separate pending file.
     * Writes directly to FileWriter (no StringBuilder) to survive low-memory situations
     * where constructing a large String could itself throw OutOfMemoryError.
     */
    public static synchronized void writeCrashPending(Context ctx, Throwable t) {
        try {
            File f = new File(ctx.getFilesDir(), CRASH_FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(ts()); fw.write(" [CRASH] "); fw.write(t.toString()); fw.write("\n");
                for (StackTraceElement e : t.getStackTrace()) {
                    fw.write("    at "); fw.write(e.toString()); fw.write("\n");
                }
                Throwable cause = t.getCause();
                if (cause != null) {
                    fw.write("  Caused by: "); fw.write(cause.toString()); fw.write("\n");
                    int max = Math.min(cause.getStackTrace().length, 10);
                    for (int i = 0; i < max; i++) {
                        fw.write("    at "); fw.write(cause.getStackTrace()[i].toString()); fw.write("\n");
                    }
                }
                fw.flush();
            }
        } catch (Throwable ignored) { }
    }

    // ── Generation sentinel ───────────────────────────────────────────────────
    // Written at generation START, deleted at generation END (success or failure).
    // If the file exists on the next app launch, the previous session crashed mid-generation.
    // This works for ALL crash types including native JNI crashes and SIGKILL,
    // which bypass the Java UncaughtExceptionHandler entirely.

    /** Write a sentinel file recording the current generation parameters. */
    public static synchronized void writeGenSentinel(Context ctx, String params) {
        try {
            File f = new File(ctx.getFilesDir(), SENTINEL_FILE);
            try (FileWriter fw = new FileWriter(f, false)) {
                fw.write(ts()); fw.write(" "); fw.write(params); fw.write("\n");
                fw.flush();
            }
        } catch (Throwable ignored) { }
    }

    /** Delete the sentinel file — call when generation finishes (success or graceful failure). */
    public static synchronized void clearGenSentinel(Context ctx) {
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), SENTINEL_FILE).delete();
    }

    /**
     * On app startup: if a sentinel file exists the previous session crashed during generation.
     * Imports the sentinel params into the main log, deletes the sentinel, and returns the
     * params line so the caller can show a dialog. Returns null if no sentinel was found.
     */
    public static synchronized String popGenSentinel(Context ctx) {
        File f = new File(ctx.getFilesDir(), SENTINEL_FILE);
        if (!f.exists()) return null;
        String params = null;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            params = br.readLine();
        } catch (IOException ignored) { }
        appendLine(ctx, LOG_FILE, ts() + " [CRASH] *** App was force-closed during generation (LMK/OOM/signal) ***");
        if (params != null) appendLine(ctx, LOG_FILE, "  last_gen: " + params);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return params != null ? params : "";
    }

    /** @deprecated Use popGenSentinel — this variant logs but discards the return value. */
    public static synchronized void checkGenSentinel(Context ctx) {
        popGenSentinel(ctx);
    }

    /** On app startup: if a pending crash file exists, move its content into the main log. */
    public static synchronized void flushCrashPending(Context ctx) {
        File f = new File(ctx.getFilesDir(), CRASH_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null)
                appendLine(ctx, LOG_FILE, line);
        } catch (IOException ignored) { }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static void appendLine(Context ctx, String filename, String line) {
        File f = new File(ctx.getFilesDir(), filename);
        List<String> lines = readLines(f);
        lines.add(line);
        if (lines.size() > MAX_LINES)
            lines = lines.subList(lines.size() - MAX_LINES, lines.size());
        writeLines(f, lines);
    }

    private static List<String> readLines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String l;
            while ((l = br.readLine()) != null) out.add(l);
        } catch (IOException ignored) { }
        return out;
    }

    private static void writeLines(File f, List<String> lines) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(f, false))) {
            for (String l : lines) { bw.write(l); bw.newLine(); }
        } catch (IOException ignored) { }
    }

    private static String ts() {
        return SDF.format(new Date());
    }
}
