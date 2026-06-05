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

    private FluxKleinConfig(Builder b) {
        this.modelPath = b.modelPath;
        this.prompt = b.prompt;
        this.seed = b.seed;
        this.steps = b.steps;
        this.imageWidth = b.imageWidth;
        this.imageHeight = b.imageHeight;
        this.inputImagePath = b.inputImagePath != null ? b.inputImagePath : "";
    }

    public static class Builder {
        private final String modelPath;
        private final String prompt;
        private int seed = -1;
        private int steps = 4;
        private int imageWidth = 512;
        private int imageHeight = 512;
        private String inputImagePath = "";

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
                '}';
    }
}
