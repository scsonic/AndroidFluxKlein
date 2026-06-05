package com.scsonic.fluxklein;

/**
 * Callback for generation progress updates (0–100).
 * Called on the same thread that FluxKlein.generate() is running on.
 */
public interface ProgressListener {
    void onProgress(int progress);
}
