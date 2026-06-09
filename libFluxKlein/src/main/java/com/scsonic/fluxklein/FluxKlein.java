package com.scsonic.fluxklein;

/**
 * Entry point for the FluxKlein image generation library.
 *
 * Add to your project:
 *   implementation project(':libFluxKlein')   // local module
 *   // or once published:
 *   implementation 'com.mnn:fluxklein:1.0.0'
 *
 * Typical usage (must be called from a background thread):
 * <pre>
 * FluxKleinConfig config = new FluxKleinConfig.Builder(modelPath, prompt)
 *     .seed(42)
 *     .imageSize(512, 512)
 *     .build();
 *
 * boolean ok = FluxKlein.generate(config, outputPath, progress -> {
 *     runOnUiThread(() -> progressBar.setProgress(progress));
 * });
 * </pre>
 */
public class FluxKlein {

    static {
        System.loadLibrary("fluxklein");
    }

    /**
     * Generate an image. <b>Blocking — must be called from a background thread.</b>
     *
     * @param config    Generation parameters (model path, prompt, size, etc.)
     * @param outPath   Full path where the output JPEG will be written
     * @param listener  Optional progress callback (0–100); may be null
     * @return true on success, false on failure
     */
    public static boolean generate(FluxKleinConfig config, String outPath,
                                   ProgressListener listener) {
        return nativeGenerate(
                config.modelPath,
                config.prompt,
                config.seed,
                config.steps,
                config.imageWidth,
                config.imageHeight,
                outPath,
                config.inputImagePath,
                config.gpuBackend,
                config.textEncoderOnCPU,
                config.vaeOnCPU,
                config.cfgScale,
                listener
        );
    }

    // ------------------------------------------------------------------
    // JNI bridge — implemented in fluxklein-jni.cpp
    // ------------------------------------------------------------------

    private static native boolean nativeGenerate(
            String modelPath,
            String prompt,
            int seed,
            int steps,
            int imageWidth,
            int imageHeight,
            String outPath,
            String inputImagePath,
            int gpuBackend,
            boolean textEncoderOnCPU,
            boolean vaeOnCPU,
            float cfgScale,
            ProgressListener listener
    );
}
