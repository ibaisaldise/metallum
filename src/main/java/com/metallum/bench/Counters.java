package com.metallum.bench;

/**
 * Cumulative backend counters. Plain fields, written only from the render thread;
 * the benchmark diffs them once per frame, so they are cheap enough to leave on.
 */
public final class Counters {
    public static long drawCalls;
    public static long renderEncoders;
    public static long blitEncoders;
    public static long commandBuffers;
    public static long pipelineBinds;
    public static long bufferBinds;
    public static long textureBinds;
    public static long samplerBinds;
    public static long texelViewsCreated;
    public static long uploadBytes;
    public static long pipelineCompiles;
    /** GPU execution time of retired command buffers. Lags the CPU by up to MAX_SUBMITS_IN_FLIGHT frames. */
    public static long gpuNanos;
    public static long gpuSamples;
    /** Render-thread time spent blocked in submit() waiting for an older frame to finish on the GPU. */
    public static long submitWaitNanos;

    static final String[] NAMES = {
            "drawCalls", "renderEncoders", "blitEncoders", "commandBuffers", "pipelineBinds",
            "bufferBinds", "textureBinds", "samplerBinds", "texelViewsCreated", "uploadBytes", "pipelineCompiles"
    };

    private Counters() {
    }

    static void snapshot(final long[] out) {
        out[0] = drawCalls;
        out[1] = renderEncoders;
        out[2] = blitEncoders;
        out[3] = commandBuffers;
        out[4] = pipelineBinds;
        out[5] = bufferBinds;
        out[6] = textureBinds;
        out[7] = samplerBinds;
        out[8] = texelViewsCreated;
        out[9] = uploadBytes;
        out[10] = pipelineCompiles;
    }
}
