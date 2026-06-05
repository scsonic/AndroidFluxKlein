package com.scsonic.fluxklein.demo;

import android.Manifest;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.scsonic.fluxklein.FluxKlein;
import com.scsonic.fluxklein.FluxKleinConfig;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG  = "FluxKleinDemo";
    private static final String DEFAULT_MODEL_PATH = "/sdcard/mnn_flux/model";
    private static final int    REQUEST_WRITE_PERMISSION = 101;
    private static final String PREFS_NAME = "fluxklein_prefs";
    private static final String KEY_HISTORY = "prompt_history";
    private static final int    MAX_HISTORY = 20;

    // Pixel thresholds for size-preset colour coding.
    // Model enforces minimum 256 on each side; max_image_seq_len=4096 tokens = (W/16)*(H/16).
    private static final long PX_GREEN  = 512L * 512;  // < this → green  (< 262k px, ~8 GB RAM)
    private static final long PX_RED    = 768L * 768;  // >= this → red   (≥ 590k px, ~16 GB RAM)
    private static final int  MAX_SEQ   = 4096;        // scheduler max_image_seq_len; beyond → over-limit

    // Size presets [width, height], all multiples of 16 and both dimensions ≥ 256
    // (model clamps any dimension below 256 to 256 automatically)
    private static final int[][] PRESETS_1_1 = {
        {256,256},{384,384},{512,512},{640,640},{768,768},{1024,1024}
    };
    private static final int[][] PRESETS_4_3 = {
        {384,288},{512,384},{640,480},{768,576},{1024,768},{1280,960}
    };
    private static final int[][] PRESETS_16_9 = {
        {512,288},{768,432},{1024,576},{1280,720}
    };

    // ── UI ────────────────────────────────────────────────────────────────────
    private MaterialButton             btnLog;
    private MaterialButtonToggleGroup  toggleGpuBackend;
    private ScrollView        scrollView;
    private TextInputEditText etPrompt;
    private MaterialButton    btnPromptHistory;
    private TextInputEditText etSeed;
    private MaterialButton    btnRandomSeed;
    private TextInputEditText etWidth;
    private TextInputEditText etHeight;
    private MaterialButton    btnSizePresets;
    private MaterialButton    btnSelectImage;
    private MaterialButton    btnClearImage;
    private ImageView         ivInputPreview;
    private MaterialButton    btnGenerate;
    private LinearLayout      layoutProgress;
    private ProgressBar       progressBar;
    private TextView          tvProgressPct;
    private TextView          tvProgressPhase;
    private View              cardError;
    private TextView          tvError;
    private LinearLayout      layoutResult;
    private ImageView         ivResult;
    private TextView          tvResultTiming;

    // ── Backend selection ─────────────────────────────────────────────────────
    // 1=Vulkan, 2=CPU (TE and VAE are always CPU — GPU causes crashes)
    private int gpuBackendType = 2;

    // ── State ─────────────────────────────────────────────────────────────────
    private String  inputImagePath = "";
    private boolean isGenerating   = false;
    private Bitmap  currentResultBitmap;
    private String  pendingGallerySavePath;

    // ── Timing (ms) ───────────────────────────────────────────────────────────
    private long startTime, timeAt0, timeAt14, timeAt71;
    private long textEncoderMs, unetMs, vaeMs;

    // ── History ───────────────────────────────────────────────────────────────
    private final List<String> promptHistory = new ArrayList<>();

    // ── Background ────────────────────────────────────────────────────────────
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> imagePicker;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bindViews();
        setupListeners();
        setupImagePicker();
        loadHistory();
        requestStoragePermission();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // =========================================================================
    // View binding & listeners
    // =========================================================================

    private void bindViews() {
        btnLog           = findViewById(R.id.btnLog);
        toggleGpuBackend = findViewById(R.id.toggleGpuBackend);
        scrollView         = findViewById(R.id.scrollView);
        etPrompt         = findViewById(R.id.etPrompt);
        btnPromptHistory = findViewById(R.id.btnPromptHistory);
        etSeed           = findViewById(R.id.etSeed);
        btnRandomSeed    = findViewById(R.id.btnRandomSeed);
        etWidth          = findViewById(R.id.etWidth);
        etHeight         = findViewById(R.id.etHeight);
        btnSizePresets   = findViewById(R.id.btnSizePresets);
        btnSelectImage   = findViewById(R.id.btnSelectImage);
        btnClearImage    = findViewById(R.id.btnClearImage);
        ivInputPreview   = findViewById(R.id.ivInputPreview);
        btnGenerate      = findViewById(R.id.btnGenerate);
        layoutProgress   = findViewById(R.id.layoutProgress);
        progressBar      = findViewById(R.id.progressBar);
        tvProgressPct    = findViewById(R.id.tvProgressPct);
        tvProgressPhase  = findViewById(R.id.tvProgressPhase);
        cardError        = findViewById(R.id.cardError);
        tvError          = findViewById(R.id.tvError);
        layoutResult     = findViewById(R.id.layoutResult);
        ivResult         = findViewById(R.id.ivResult);
        tvResultTiming   = findViewById(R.id.tvResultTiming);
    }

    private void setupListeners() {
        btnRandomSeed.setOnClickListener(v ->
            etSeed.setText(String.valueOf(new Random().nextInt(Integer.MAX_VALUE))));
        btnSelectImage.setOnClickListener(v -> imagePicker.launch("image/*"));
        btnClearImage.setOnClickListener(v  -> clearInputImage());
        btnGenerate.setOnClickListener(v    -> startGeneration());
        btnPromptHistory.setOnClickListener(this::showPromptHistoryPopup);
        btnSizePresets.setOnClickListener(this::showSizePresetsPopup);
        btnLog.setOnClickListener(v -> showLogDialog());
        ivResult.setOnClickListener(v -> {
            if (currentResultBitmap != null) showFullscreenImage(currentResultBitmap);
        });

        toggleGpuBackend.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) gpuBackendType = (checkedId == R.id.btnTransformerVulkan) ? 1 : 2;
        });
    }

    // =========================================================================
    // Image picker
    // =========================================================================

    private void setupImagePicker() {
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return;
                File f = new File(getCacheDir(), "input_" + UUID.randomUUID() + ".jpg");
                FileOutputStream fos = new FileOutputStream(f);
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                is.close(); fos.close();
                inputImagePath = f.getAbsolutePath();
                ivInputPreview.setImageURI(uri);
                ivInputPreview.setVisibility(View.VISIBLE);
                btnClearImage.setVisibility(View.VISIBLE);
                btnSelectImage.setText(R.string.change_image);
            } catch (Exception e) {
                Log.e(TAG, "Error copying image", e);
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void clearInputImage() {
        inputImagePath = "";
        ivInputPreview.setImageDrawable(null);
        ivInputPreview.setVisibility(View.GONE);
        btnClearImage.setVisibility(View.GONE);
        btnSelectImage.setText(R.string.select_image);
    }

    // =========================================================================
    // Prompt history
    // =========================================================================

    private void loadHistory() {
        String json = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_HISTORY, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            promptHistory.clear();
            for (int i = 0; i < arr.length(); i++) promptHistory.add(arr.getString(i));
        } catch (JSONException ignored) { promptHistory.clear(); }
    }

    private void saveHistory() {
        JSONArray arr = new JSONArray();
        for (String s : promptHistory) arr.put(s);
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    private void addToHistory(String prompt) {
        promptHistory.remove(prompt);
        promptHistory.add(0, prompt);
        if (promptHistory.size() > MAX_HISTORY)
            promptHistory.subList(MAX_HISTORY, promptHistory.size()).clear();
        saveHistory();
    }

    private void showPromptHistoryPopup(View anchor) {
        if (promptHistory.isEmpty()) {
            Toast.makeText(this, R.string.no_history, Toast.LENGTH_SHORT).show();
            return;
        }
        ListPopupWindow popup = new ListPopupWindow(this);
        popup.setAnchorView(etPrompt);
        popup.setWidth(ListPopupWindow.WRAP_CONTENT);
        popup.setModal(true);
        popup.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_1, promptHistory));
        popup.setOnItemClickListener((parent, view, pos, id) -> {
            etPrompt.setText(promptHistory.get(pos));
            etPrompt.setSelection(etPrompt.getText() != null ? etPrompt.getText().length() : 0);
            popup.dismiss();
        });
        popup.show();
    }

    // =========================================================================
    // Size presets popup
    // =========================================================================

    private void showSizePresetsPopup(View anchor) {
        // Build the popup view tree programmatically
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(4), dp(8), dp(4), dp(8));
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.popup_bg));

        // We store the PopupWindow reference after creation so item clicks can dismiss it
        android.widget.PopupWindow[] popupRef = new android.widget.PopupWindow[1];

        addPresetSection(root, "1 : 1",  PRESETS_1_1,  popupRef);
        addPresetDivider(root);
        addPresetSection(root, "4 : 3",  PRESETS_4_3,  popupRef);
        addPresetDivider(root);
        addPresetSection(root, "16 : 9", PRESETS_16_9, popupRef);
        addPresetDivider(root);

        // Legend
        TextView note = new TextView(this);
        note.setPadding(dp(12), dp(8), dp(12), dp(6));
        note.setTextSize(11f);
        note.setTextColor(ContextCompat.getColor(this, R.color.popup_section_text));
        note.setText("🟢 <512×512 ~8 GB   🟡 ~12 GB   🔴 ≥768×768 ~16 GB\n⚠️ seq>4096：超出訓練範圍，不建議使用\nMin each side: 256 px (model limit)");
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);

        android.widget.PopupWindow popup = new android.widget.PopupWindow(
            sv, dp(250), WindowManager.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setElevation(12f);
        popup.setBackgroundDrawable(new ColorDrawable(
            ContextCompat.getColor(this, R.color.popup_bg)));
        popupRef[0] = popup;
        popup.showAsDropDown(anchor, 0, dp(4));
    }

    private void addPresetSection(LinearLayout parent, String label,
                                  int[][] presets, android.widget.PopupWindow[] popupRef) {
        TextView header = new TextView(this);
        header.setText(label);
        header.setTextSize(12f);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setTextColor(ContextCompat.getColor(this, R.color.popup_section_text));
        header.setPadding(dp(12), dp(8), dp(12), dp(4));
        parent.addView(header);

        for (int[] p : presets) {
            int w = p[0], h = p[1];
            TextView item = new TextView(this);
            item.setText(isOverSeqLimit(w, h) ? w + " × " + h + "  ⚠️" : w + " × " + h);
            item.setTextSize(14f);
            item.setPadding(dp(20), dp(10), dp(20), dp(10));

            int[] colors = presetColors(w, h);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(colors[0]);
            bg.setCornerRadius(dp(8));
            item.setBackground(bg);
            item.setTextColor(colors[1]);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), dp(2), dp(8), dp(2));
            item.setLayoutParams(lp);
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnClickListener(v -> {
                etWidth.setText(String.valueOf(w));
                etHeight.setText(String.valueOf(h));
                if (popupRef[0] != null) popupRef[0].dismiss();
            });
            parent.addView(item);
        }
    }

    private void addPresetDivider(LinearLayout parent) {
        View div = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMargins(dp(8), dp(6), dp(8), dp(6));
        div.setLayoutParams(lp);
        div.setBackgroundColor(ContextCompat.getColor(this, R.color.popup_divider));
        parent.addView(div);
    }

    /** True when (W/16)*(H/16) > MAX_SEQ — beyond the model's scheduler training range. */
    private boolean isOverSeqLimit(int w, int h) {
        return (w / 16) * (h / 16) > MAX_SEQ;
    }

    /** Returns [bgColor, textColor] for a preset item based on pixel count. */
    private int[] presetColors(int w, int h) {
        if (isOverSeqLimit(w, h)) return new int[]{
            ContextCompat.getColor(this, R.color.preset_overlimit_bg),
            ContextCompat.getColor(this, R.color.preset_overlimit_text)};
        long px = (long) w * h;
        if (px < PX_GREEN) return new int[]{
            ContextCompat.getColor(this, R.color.preset_green_bg),
            ContextCompat.getColor(this, R.color.preset_green_text)};
        if (px >= PX_RED)  return new int[]{
            ContextCompat.getColor(this, R.color.preset_red_bg),
            ContextCompat.getColor(this, R.color.preset_red_text)};
        return new int[]{
            ContextCompat.getColor(this, R.color.preset_yellow_bg),
            ContextCompat.getColor(this, R.color.preset_yellow_text)};
    }

    // =========================================================================
    // Generation
    // =========================================================================

    private void startGeneration() {
        if (isGenerating) return;
        String prompt = etPrompt.getText() != null ? etPrompt.getText().toString().trim() : "";
        if (TextUtils.isEmpty(prompt)) {
            Toast.makeText(this, "Please enter a prompt", Toast.LENGTH_SHORT).show();
            return;
        }
        int seed   = parseSeed();
        int width  = parseDimension(etWidth, 512);
        int height = parseDimension(etHeight, 512);
        String outPath = new File(getCacheDir(), "out_" + UUID.randomUUID() + ".jpg").getAbsolutePath();

        addToHistory(prompt);

        String tfLabel = gpuBackendType == 1 ? "Vulkan" : "CPU";
        String genParams = "size=" + width + "x" + height
            + " seed=" + seed + " steps=4"
            + (inputImagePath.isEmpty() ? "" : " img2img=true")
            + " seq=" + ((width / 16) * (height / 16))
            + " tf=" + tfLabel
            + " prompt=\"" + prompt.replace("\"", "\\\"") + "\"";

        // Sentinel: survives native crashes / SIGKILL that bypass the Java UncaughtExceptionHandler.
        // Cleared in mainHandler.post() which only runs when the executor thread finishes normally.
        AppLogger.writeGenSentinel(this, genParams);

        AppLogger.log(this, "GEN", "START " + genParams);

        FluxKleinConfig config = new FluxKleinConfig.Builder(DEFAULT_MODEL_PATH, prompt)
            .seed(seed).steps(4).imageSize(width, height).inputImagePath(inputImagePath)
            .gpuBackend(gpuBackendType).textEncoderOnCPU(true).vaeOnCPU(true)
            .build();

        isGenerating = true;
        currentResultBitmap = null;
        showGenerating();

        startTime = System.currentTimeMillis();
        timeAt0 = timeAt14 = timeAt71 = startTime;
        textEncoderMs = unetMs = vaeMs = 0;

        executor.submit(() -> {
            boolean success = false;
            String errMsg = null;
            try {
                success = FluxKlein.generate(config, outPath, progress -> {
                    long now = System.currentTimeMillis();
                    if (progress == 0)                    timeAt0 = now;
                    if (progress == 14)                 { timeAt14 = now; textEncoderMs = timeAt14 - timeAt0; }
                    if (progress >= 15 && progress <= 71) { timeAt71 = now; unetMs = timeAt71 - timeAt14; }
                    if (progress == 100)                  vaeMs = now - timeAt71;
                    long elapsed = now - startTime;
                    mainHandler.post(() -> updateProgress(progress, elapsed));
                });
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "OOM during generation", oom);
                AppLogger.log(MainActivity.this, "OOM",
                    "size=" + width + "x" + height
                    + " seq=" + ((width / 16) * (height / 16))
                    + " | " + oom);
                errMsg = "Out of memory!\nTry a smaller image size.\n(current: " + width + "×" + height + ")";
            } catch (Throwable t) {
                Log.e(TAG, "Generation exception", t);
                AppLogger.log(MainActivity.this, "ERR",
                    "size=" + width + "x" + height + " | " + t);
                errMsg = "Generation failed: " + t.getMessage();
            }

            long totalMs = System.currentTimeMillis() - startTime;
            if (success) {
                AppLogger.log(MainActivity.this, "GEN",
                    "END   success=true  total=" + fmtMs(totalMs)
                    + (textEncoderMs > 0 ? String.format(Locale.US,
                        "  textEnc=%s 512tok %.2ftok/s", fmtMs(textEncoderMs),
                        512000.0 / textEncoderMs) : "")
                    + (unetMs > 0 ? "  unet=" + fmtMs(unetMs) : "")
                    + (vaeMs  > 0 ? "  vae="  + fmtMs(vaeMs)  : ""));
            } else {
                AppLogger.log(MainActivity.this, "GEN",
                    "END   success=false total=" + fmtMs(totalMs));
            }

            boolean ok       = success;
            String  finalErr = errMsg;
            mainHandler.post(() -> {
                isGenerating = false;
                // Generation finished normally (success or graceful error) — clear sentinel.
                // If the app crashed before reaching here, sentinel remains for next startup.
                AppLogger.clearGenSentinel(MainActivity.this);
                if (ok) showResult(outPath, totalMs);
                else    showError(finalErr != null ? finalErr
                            : "Generation failed.\nEnsure model files exist at:\n"
                            + DEFAULT_MODEL_PATH + "\n\nadb push <model_dir> /sdcard/mnn_flux/model");
            });
        });
    }

    private int parseSeed() {
        String s = etSeed.getText() != null ? etSeed.getText().toString().trim() : "";
        if (TextUtils.isEmpty(s)) return new Random().nextInt(Integer.MAX_VALUE);
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 42; }
    }

    private int parseDimension(TextInputEditText et, int def) {
        try {
            String s = et.getText() != null ? et.getText().toString().trim() : "";
            return Math.max(256, Integer.parseInt(s));
        } catch (NumberFormatException e) { return def; }
    }

    // =========================================================================
    // UI state helpers
    // =========================================================================

    private void showGenerating() {
        cardError.setVisibility(View.GONE);
        layoutResult.setVisibility(View.GONE);
        layoutProgress.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvProgressPct.setText("0%");
        tvProgressPhase.setText("");
        btnGenerate.setEnabled(false);
        btnGenerate.setText(R.string.generating);
    }

    private void updateProgress(int pct, long elapsedMs) {
        progressBar.setProgress(pct);
        tvProgressPct.setText(pct + "%");
        String phase = pct <= 14 ? "Text Encoding…" : pct <= 71 ? "Diffusion (UNet)…" : "VAE Decode…";
        StringBuilder sb = new StringBuilder(phase).append("\n");
        if (textEncoderMs > 0) sb.append(fmtTeStats(textEncoderMs)).append("\n");
        if (unetMs        > 0) sb.append("UNet        : ").append(fmtMs(unetMs)).append("\n");
        if (vaeMs         > 0) sb.append("VAE         : ").append(fmtMs(vaeMs)).append("\n");
        sb.append("Elapsed     : ").append(fmtMs(elapsedMs));
        tvProgressPhase.setText(sb.toString());
    }

    private void showResult(String outPath, long totalMs) {
        layoutProgress.setVisibility(View.GONE);
        btnGenerate.setEnabled(true);
        btnGenerate.setText(R.string.generate);

        File f = new File(outPath);
        if (f.exists()) {
            currentResultBitmap = BitmapFactory.decodeFile(outPath);
            ivResult.setImageBitmap(currentResultBitmap);
            layoutResult.setVisibility(View.VISIBLE);
            executor.submit(() -> saveToGallery(outPath));
        }

        StringBuilder sb = new StringBuilder();
        if (textEncoderMs > 0) sb.append(fmtTeStats(textEncoderMs)).append("\n");
        if (unetMs        > 0) sb.append("UNet        : ").append(fmtMs(unetMs)).append("\n");
        if (vaeMs         > 0) sb.append("VAE Decode  : ").append(fmtMs(vaeMs)).append("\n");
        sb.append("Total       : ").append(fmtMs(totalMs));
        tvResultTiming.setText(sb.toString());
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void showError(String msg) {
        layoutProgress.setVisibility(View.GONE);
        btnGenerate.setEnabled(true);
        btnGenerate.setText(R.string.generate);
        tvError.setText(msg);
        cardError.setVisibility(View.VISIBLE);
    }

    // =========================================================================
    // Log dialog
    // =========================================================================

    private void showLogDialog() {
        String content = AppLogger.readAll(this);

        // Monospace text view
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(content);
        tv.setTextSize(10.5f);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int pad = dp(10);
        tv.setPadding(pad, pad, pad, pad);

        // Wrap in a ScrollView so long logs are scrollable
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.log_title)
            .setView(sv)
            .setPositiveButton(R.string.log_close, null)
            .setNeutralButton(R.string.log_clear, (d, w) -> {
                AppLogger.clearLog(this);
                AppLogger.log(this, "APP", "Log cleared by user");
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show();
            })
            .create();

        dialog.show();

        // Auto-scroll to bottom after the view is laid out
        sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
    }

    // =========================================================================
    // Fullscreen image viewer
    // =========================================================================

    private void showFullscreenImage(Bitmap bitmap) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(bitmap);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(Color.BLACK);
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(iv);
        dialog.show();
    }

    // =========================================================================
    // Save to gallery
    // =========================================================================

    private void saveToGallery(String srcPath) {
        String ts  = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fn  = "FluxKlein_" + ts + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, fn);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "FluxKlein");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri);
                         FileInputStream fis = new FileInputStream(srcPath)) {
                        byte[] buf = new byte[8192]; int n;
                        while ((n = fis.read(buf)) != -1) { if (os != null) os.write(buf, 0, n); }
                    }
                    cv.clear();
                    cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                    getContentResolver().update(uri, cv, null, null);
                    mainHandler.post(() ->
                        Toast.makeText(this, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show());
                }
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingGallerySavePath = srcPath;
                    ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_WRITE_PERMISSION);
                    return;
                }
                saveToGalleryLegacy(srcPath, fn);
            }
        } catch (Exception e) { Log.e(TAG, "saveToGallery failed", e); }
    }

    private void saveToGalleryLegacy(String srcPath, String filename) {
        try {
            File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "FluxKlein");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File dest = new File(dir, filename);
            try (FileInputStream fis = new FileInputStream(srcPath);
                 FileOutputStream fos = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = fis.read(buf)) != -1) fos.write(buf, 0, n);
            }
            MediaScannerConnection.scanFile(this,
                new String[]{dest.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
            mainHandler.post(() ->
                Toast.makeText(this, R.string.saved_to_gallery, Toast.LENGTH_SHORT).show());
        } catch (Exception e) { Log.e(TAG, "saveToGalleryLegacy failed", e); }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQUEST_WRITE_PERMISSION
                && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED
                && pendingGallerySavePath != null) {
            String path = pendingGallerySavePath;
            pendingGallerySavePath = null;
            String fn = "FluxKlein_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            executor.submit(() -> saveToGalleryLegacy(path, fn));
        }
    }

    // =========================================================================
    // Storage permission for model reading (Android 11+)
    // =========================================================================

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.addCategory(Intent.CATEGORY_DEFAULT);
                    i.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                }
            }
        } else {
            requestPermissions(new String[]{
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 100);
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private int dp(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }

    private static String fmtMs(long ms) {
        if (ms <= 0) return "0 ms";
        long s = ms / 1000, m = s / 60;
        return m > 0 ? ms + " ms (" + m + "m " + (s % 60) + "s)" : ms + " ms (" + s + "s)";
    }

    /** Text-encoder timing line: time + fixed 512-token throughput. */
    private static String fmtTeStats(long ms) {
        double tokPerSec = ms > 0 ? 512000.0 / ms : 0;
        return String.format(Locale.US,
            "TextEncoder : %s   512 tok  %.2f tok/s", fmtMs(ms), tokPerSec);
    }

}
