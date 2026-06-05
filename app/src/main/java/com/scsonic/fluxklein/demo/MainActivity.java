package com.scsonic.fluxklein.demo;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.scsonic.fluxklein.FluxKlein;
import com.scsonic.fluxklein.FluxKleinConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FluxKleinDemo";
    private static final String DEFAULT_MODEL_PATH = "/sdcard/mnn_flux/model";

    // ---- UI ----
    private EditText etPrompt;
    private EditText etSeed;
    private EditText etWidth;
    private EditText etHeight;
    private Button   btnRandomSeed;
    private Button   btnSelectImage;
    private Button   btnClearImage;
    private ImageView ivInputPreview;
    private Button   btnGenerate;
    private LinearLayout layoutProgress;
    private ProgressBar  progressBar;
    private TextView tvProgressPct;
    private TextView tvProgressPhase;
    private LinearLayout layoutResult;
    private ImageView    ivResult;
    private TextView     tvResultTiming;
    private TextView tvError;
    private ScrollView scrollView;

    // ---- State ----
    private String  inputImagePath = "";
    private boolean isGenerating   = false;

    // ---- Timing (all in ms) ----
    private long startTime;
    private long timeAt0, timeAt14, timeAt71;
    private long textEncoderMs, unetMs, vaeMs;

    // ---- Background worker ----
    private final ExecutorService executor    = Executors.newSingleThreadExecutor();
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    // ---- Image picker ----
    private ActivityResultLauncher<String> imagePicker;

    // =========================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        setupImagePicker();
        requestStoragePermission();
    }

    private void bindViews() {
        scrollView       = findViewById(R.id.scrollView);
        etPrompt         = findViewById(R.id.etPrompt);
        etSeed           = findViewById(R.id.etSeed);
        etWidth          = findViewById(R.id.etWidth);
        etHeight         = findViewById(R.id.etHeight);
        btnRandomSeed    = findViewById(R.id.btnRandomSeed);
        btnSelectImage   = findViewById(R.id.btnSelectImage);
        btnClearImage    = findViewById(R.id.btnClearImage);
        ivInputPreview   = findViewById(R.id.ivInputPreview);
        btnGenerate      = findViewById(R.id.btnGenerate);
        layoutProgress   = findViewById(R.id.layoutProgress);
        progressBar      = findViewById(R.id.progressBar);
        tvProgressPct    = findViewById(R.id.tvProgressPct);
        tvProgressPhase  = findViewById(R.id.tvProgressPhase);
        layoutResult     = findViewById(R.id.layoutResult);
        ivResult         = findViewById(R.id.ivResult);
        tvResultTiming   = findViewById(R.id.tvResultTiming);
        tvError          = findViewById(R.id.tvError);
    }

    private void setupListeners() {
        btnRandomSeed.setOnClickListener(v -> {
            int randomSeed = new Random().nextInt(Integer.MAX_VALUE);
            etSeed.setText(String.valueOf(randomSeed));
        });

        btnSelectImage.setOnClickListener(v -> imagePicker.launch("image/*"));

        btnClearImage.setOnClickListener(v -> clearInputImage());

        btnGenerate.setOnClickListener(v -> startGeneration());
    }

    private void setupImagePicker() {
        imagePicker = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri == null) return;
                    try {
                        InputStream is = getContentResolver().openInputStream(uri);
                        if (is == null) return;
                        File cacheFile = new File(getCacheDir(),
                                "input_" + UUID.randomUUID() + ".jpg");
                        FileOutputStream fos = new FileOutputStream(cacheFile);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                        is.close();
                        fos.close();

                        inputImagePath = cacheFile.getAbsolutePath();
                        ivInputPreview.setImageURI(uri);
                        ivInputPreview.setVisibility(View.VISIBLE);
                        btnClearImage.setVisibility(View.VISIBLE);
                        btnSelectImage.setText(R.string.change_image);
                    } catch (Exception e) {
                        Log.e(TAG, "Error copying picked image", e);
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

    private void clearInputImage() {
        inputImagePath = "";
        ivInputPreview.setImageDrawable(null);
        ivInputPreview.setVisibility(View.GONE);
        btnClearImage.setVisibility(View.GONE);
        btnSelectImage.setText(R.string.select_image);
    }

    // =========================================================
    // Generation
    // =========================================================

    private void startGeneration() {
        if (isGenerating) return;

        String prompt = etPrompt.getText().toString().trim();
        if (TextUtils.isEmpty(prompt)) {
            Toast.makeText(this, "Please enter a prompt", Toast.LENGTH_SHORT).show();
            return;
        }

        int seed  = parseSeed();
        int width = parseDimension(etWidth, 512);
        int height = parseDimension(etHeight, 512);
        String outPath = new File(getCacheDir(),
                "out_" + UUID.randomUUID() + ".jpg").getAbsolutePath();

        FluxKleinConfig config = new FluxKleinConfig.Builder(DEFAULT_MODEL_PATH, prompt)
                .seed(seed)
                .steps(4)
                .imageSize(width, height)
                .inputImagePath(inputImagePath)
                .build();

        isGenerating = true;
        showGenerating();

        startTime = System.currentTimeMillis();
        timeAt0 = timeAt14 = timeAt71 = startTime;
        textEncoderMs = unetMs = vaeMs = 0;

        executor.submit(() -> {
            boolean success;
            try {
                success = FluxKlein.generate(config, outPath, progress -> {
                    long now = System.currentTimeMillis();
                    if (progress == 0)  timeAt0 = now;
                    if (progress == 14) { timeAt14 = now; textEncoderMs = timeAt14 - timeAt0; }
                    if (progress >= 15 && progress <= 71) { timeAt71 = now; unetMs = timeAt71 - timeAt14; }
                    if (progress == 100) vaeMs = now - timeAt71;
                    long elapsed = now - startTime;
                    mainHandler.post(() -> updateProgress(progress, elapsed));
                });
            } catch (Exception e) {
                Log.e(TAG, "Generation threw exception", e);
                success = false;
            }

            long totalMs = System.currentTimeMillis() - startTime;
            boolean finalSuccess = success;
            String finalOutPath = outPath;
            mainHandler.post(() -> {
                isGenerating = false;
                if (finalSuccess) {
                    showResult(finalOutPath, totalMs);
                } else {
                    showError("Generation failed.\nMake sure model files are at:\n" + DEFAULT_MODEL_PATH
                            + "\n\nRun: adb push <model_dir> /sdcard/mnn_flux/model");
                }
            });
        });
    }

    private int parseSeed() {
        String s = etSeed.getText().toString().trim();
        if (TextUtils.isEmpty(s)) {
            return new Random().nextInt(Integer.MAX_VALUE);
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 42;
        }
    }

    private int parseDimension(EditText et, int defaultVal) {
        try {
            int v = Integer.parseInt(et.getText().toString().trim());
            return Math.max(64, v);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // =========================================================
    // UI state helpers
    // =========================================================

    private void showGenerating() {
        tvError.setVisibility(View.GONE);
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

        String phase;
        if (pct <= 14)       phase = "Text Encoding…";
        else if (pct <= 71)  phase = "Diffusion (UNet)…";
        else                 phase = "VAE Decode…";

        StringBuilder sb = new StringBuilder(phase).append("\n");
        if (textEncoderMs > 0) sb.append("TextEncoder: ").append(fmtMs(textEncoderMs)).append("\n");
        if (unetMs        > 0) sb.append("UNet: ").append(fmtMs(unetMs)).append("\n");
        if (vaeMs         > 0) sb.append("VAE: ").append(fmtMs(vaeMs)).append("\n");
        sb.append("Elapsed: ").append(fmtMs(elapsedMs));
        tvProgressPhase.setText(sb.toString());
    }

    private void showResult(String outPath, long totalMs) {
        layoutProgress.setVisibility(View.GONE);
        btnGenerate.setEnabled(true);
        btnGenerate.setText(R.string.generate);

        File f = new File(outPath);
        if (f.exists()) {
            // Decode with sample size to avoid OOM for large outputs
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            Bitmap bmp = BitmapFactory.decodeFile(outPath, opts);
            ivResult.setImageBitmap(bmp);
            layoutResult.setVisibility(View.VISIBLE);
        }

        StringBuilder sb = new StringBuilder();
        if (textEncoderMs > 0) sb.append("TextEncoder : ").append(fmtMs(textEncoderMs)).append("\n");
        if (unetMs        > 0) sb.append("UNet        : ").append(fmtMs(unetMs)).append("\n");
        if (vaeMs         > 0) sb.append("VAE Decode  : ").append(fmtMs(vaeMs)).append("\n");
        sb.append("Total       : ").append(fmtMs(totalMs));
        tvResultTiming.setText(sb.toString());

        // Scroll to result
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void showError(String msg) {
        layoutProgress.setVisibility(View.GONE);
        btnGenerate.setEnabled(true);
        btnGenerate.setText(R.string.generate);
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private static String fmtMs(long ms) {
        if (ms <= 0) return "0 ms";
        long sec = ms / 1000;
        long m   = sec / 60;
        long s   = sec % 60;
        if (m > 0) return ms + " ms (" + m + "m " + s + "s)";
        return ms + " ms (" + s + "s)";
    }

    // =========================================================
    // Storage permission
    // =========================================================

    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory(Intent.CATEGORY_DEFAULT);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 100);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
