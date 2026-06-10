package com.scsonic.fluxklein;

/**
 * Immutable configuration for a single FluxKlein generation request.
 *
 * Usage:
 * <pre>
 * FluxKleinConfig config = new FluxKleinConfig.Builder("/sdcard/mnn_flux/model", "a cat")
 *     .seed(42)
 *     .imageSize(512, 512)
 *     .build();
 * </pre>
 */
public class FluxKleinConfig {

    public final String modelPath;
    public final String prompt;
    /** -1 means pick a random seed at generation time. */
    public final int seed;
    public final int steps;
    public final int imageWidth;
    public final int imageHeight;
    /** Empty string means no reference image (text-to-image). */
    public final String inputImagePath;
    /** GPU backend constants for {@link #gpuBackend}. */
    public static final int GPU_BACKEND_OPENCL = 0;
    public static final int GPU_BACKEND_VULKAN  = 1;
    public static final int GPU_BACKEND_CPU     = 2;
    public static final int GPU_BACKEND_NPU     = 3; // Qualcomm HTP/NPU via QNN (MNN_FORWARD_NN)

    /** GPU backend for the UNet (and any GPU-assigned components). See GPU_BACKEND_* constants. */
    public final int gpuBackend;
    /** True = run text encoder on CPU; false = run on the selected GPU backend. */
    public final boolean textEncoderOnCPU;
    /** True = run VAE on CPU; false = run on the selected GPU backend. */
    public final boolean vaeOnCPU;
    /** Classifier-free guidance scale. 1.0 = no CFG (FLUX default). Range [0, 5]. */
    public final float cfgScale;

    private FluxKleinConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.prompt = b.prompt;
        this.seed = b.seed;
        this.steps = b.steps;
        this.imageWidth = b.imageWidth;
        this.imageHeight = b.imageHeight;
        this.inputImagePath = b.inputImagePath != null ? b.inputImagePath : "";
        this.gpuBackend = b.gpuBackend;
        this.textEncoderOnCPU = b.textEncoderOnCPU;
        this.vaeOnCPU = b.vaeOnCPU;
        this.cfgScale = b.cfgScale;
    }

    public static class Builder {
        private final String modelPath;
        private final String prompt;
        private int seed = -1;
        private int steps = 4;
        private int imageWidth = 512;
        private int imageHeight = 512;
        private String inputImagePath = "";
        private int gpuBackend = GPU_BACKEND_OPENCL; // OpenCL is the confirmed-stable default
        private boolean textEncoderOnCPU = true;
        private boolean vaeOnCPU = true;
        private float cfgScale = 1.0f;

        public Builder(String modelPath, String prompt) {
            if (modelPath == null || modelPath.isEmpty())
                throw new IllegalArgumentException("modelPath must not be empty");
            if (prompt == null || prompt.isEmpty())
                throw new IllegalArgumentException("prompt must not be empty");
            this.modelPath = modelPath;
            this.prompt = prompt;
        }

        /** Set the random seed. Use -1 to let the library pick one. */
        public Builder seed(int seed) {
            this.seed = seed;
            return this;
        }

        /** Number of diffusion steps. Default is 4 (FLUX.2-Klein recommended). */
        public Builder steps(int steps) {
            if (steps < 1) throw new IllegalArgumentException("steps must be >= 1");
            this.steps = steps;
            return this;
        }

        /** Output image dimensions. Both values must be positive. Default 512x512. */
        public Builder imageSize(int width, int height) {
            if (width < 64 || height < 64)
                throw new IllegalArgumentException("Image size must be at least 64x64");
            this.imageWidth = width;
            this.imageHeight = height;
            return this;
        }

        /** Path to a reference image for image-to-image generation. Null or "" disables it. */
        public Builder inputImagePath(String path) {
            this.inputImagePath = path != null ? path : "";
            return this;
        }

        /** GPU backend: see GPU_BACKEND_* constants. Applies to UNet and any GPU-assigned modules. */
        public Builder gpuBackend(int backend) { this.gpuBackend = backend; return this; }

        /** True = text encoder on CPU (safer, default); false = text encoder on the GPU backend. */
        public Builder textEncoderOnCPU(boolean onCPU) { this.textEncoderOnCPU = onCPU; return this; }

        /** True = VAE on CPU (default); false = VAE on the GPU backend (typically faster). */
        public Builder vaeOnCPU(boolean onCPU) { this.vaeOnCPU = onCPU; return this; }

        /** Classifier-free guidance scale. 1.0 = no CFG (FLUX default). Range [0, 5]. */
        public Builder cfgScale(float scale) { this.cfgScale = Math.max(0f, Math.min(5f, scale)); return this; }

        public FluxKleinConfig build() {
            return new FluxKleinConfig(this);
        }
    }

    @Override
    public String toString() {
        return "FluxKleinConfig{" +
                "prompt='" + prompt + '\'' +
                ", seed=" + seed +
                ", steps=" + steps +
                ", size=" + imageWidth + "x" + imageHeight +
                ", inputImage='" + (inputImagePath.isEmpty() ? "none" : inputImagePath) + '\'' +
                ", gpu=" + (gpuBackend == GPU_BACKEND_VULKAN ? "Vulkan"
                         : gpuBackend == GPU_BACKEND_CPU    ? "CPU"
                         : gpuBackend == GPU_BACKEND_NPU    ? "NPU(QNN)"
                         : "OpenCL") +
                ", te=" + (textEncoderOnCPU ? "CPU" : "GPU") +
                ", vae=" + (vaeOnCPU ? "CPU" : "GPU") +
                '}';
    }
}
