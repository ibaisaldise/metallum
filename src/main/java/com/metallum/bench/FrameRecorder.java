package com.metallum.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.metallum.Metallum;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

final class FrameRecorder {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final LongArrayList frameNanos = new LongArrayList();
    private final long[] countersAtStart = new long[Counters.NAMES.length];
    private final long[] countersNow = new long[Counters.NAMES.length];
    private long startedAt = -1L;
    private long lastFrameAt;
    private long gpuNanosAtStart;
    private long gpuSamplesAtStart;
    private long submitWaitAtStart;

    void start(final long now) {
        startedAt = now;
        lastFrameAt = now;
        Counters.snapshot(countersAtStart);
        gpuNanosAtStart = Counters.gpuNanos;
        gpuSamplesAtStart = Counters.gpuSamples;
        submitWaitAtStart = Counters.submitWaitNanos;
    }

    void frame(final long now) {
        frameNanos.add(now - lastFrameAt);
        lastFrameAt = now;
    }

    /** Returns the results file, or null if it couldn't be written. */
    @Nullable
    Path writeResults(final Minecraft mc, @Nullable final String error) {
        JsonObject root = new JsonObject();
        root.addProperty("label", System.getProperty("metallum.bench.label", "run"));
        root.addProperty("commit", System.getProperty("metallum.bench.commit", "unknown"));
        root.addProperty("timestamp", LocalDateTime.now().toString());
        if (error != null) {
            root.addProperty("error", error);
        }
        root.add("environment", environment(mc));
        if (startedAt >= 0L && !frameNanos.isEmpty()) {
            root.add("summary", summary());
            JsonArray frames = new JsonArray(frameNanos.size());
            for (int i = 0; i < frameNanos.size(); i++) {
                frames.add(round(frameNanos.getLong(i) / 1.0e6, 3));
            }
            root.add("frameMs", frames);
        }

        Path dir = FabricLoader.getInstance().getGameDir().resolve("metallum-bench");
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = dir.resolve(sanitize(root.get("label").getAsString()) + "-" + stamp + ".json");
        try {
            Files.createDirectories(dir);
            Files.writeString(file, GSON.toJson(root));
            Metallum.LOGGER.info("[metallum-bench] results written to {}", file.toAbsolutePath());
            return file;
        } catch (IOException e) {
            Metallum.LOGGER.error("[metallum-bench] failed to write results", e);
            return null;
        }
    }

    private JsonObject summary() {
        long totalNanos = lastFrameAt - startedAt;
        int frames = frameNanos.size();
        long[] sorted = frameNanos.toLongArray();
        Arrays.sort(sorted);

        JsonObject summary = new JsonObject();
        summary.addProperty("frames", frames);
        summary.addProperty("seconds", round(totalNanos / 1.0e9, 3));
        summary.addProperty("avgFps", round(frames / (totalNanos / 1.0e9), 2));
        // Average FPS over the slowest 1% of frames.
        int worst = Math.max(1, frames / 100);
        long worstSum = 0L;
        for (int i = frames - worst; i < frames; i++) {
            worstSum += sorted[i];
        }
        summary.addProperty("onePercentLowFps", round(worst / (worstSum / 1.0e9), 2));

        JsonObject frameMs = new JsonObject();
        frameMs.addProperty("mean", round(totalNanos / 1.0e6 / frames, 3));
        frameMs.addProperty("p50", ms(percentile(sorted, 0.50)));
        frameMs.addProperty("p90", ms(percentile(sorted, 0.90)));
        frameMs.addProperty("p99", ms(percentile(sorted, 0.99)));
        frameMs.addProperty("max", ms(sorted[frames - 1]));
        summary.add("frameMs", frameMs);

        long gpuSamples = Counters.gpuSamples - gpuSamplesAtStart;
        long gpuNanos = Counters.gpuNanos - gpuNanosAtStart;
        long submitWait = Counters.submitWaitNanos - submitWaitAtStart;
        if (gpuSamples > 0) {
            // Only the Metal backend feeds these.
            summary.addProperty("gpuMsPerSubmit", round(gpuNanos / 1.0e6 / gpuSamples, 3));
            summary.addProperty("gpuBusyPercent", round(100.0 * gpuNanos / totalNanos, 1));
            summary.addProperty("submitWaitMsPerFrame", round(submitWait / 1.0e6 / frames, 3));
            summary.addProperty("cpuMsPerFrame", round((totalNanos - submitWait) / 1.0e6 / frames, 3));
        }

        Counters.snapshot(countersNow);
        JsonObject perFrame = new JsonObject();
        for (int i = 0; i < Counters.NAMES.length; i++) {
            perFrame.addProperty(Counters.NAMES[i], round((double) (countersNow[i] - countersAtStart[i]) / frames, 2));
        }
        summary.add("perFrame", perFrame);
        return summary;
    }

    private static JsonObject environment(final Minecraft mc) {
        JsonObject env = new JsonObject();
        try {
            DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
            env.addProperty("backend", info.backendName());
            env.addProperty("device", info.name());
        } catch (RuntimeException e) {
            env.addProperty("backend", "unknown");
        }
        env.addProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        env.addProperty("java", System.getProperty("java.version"));
        env.addProperty("sodium", FabricLoader.getInstance().isModLoaded("sodium"));
        env.addProperty("framebufferWidth", mc.getWindow().getWidth());
        env.addProperty("framebufferHeight", mc.getWindow().getHeight());
        env.addProperty("renderDistance", mc.options.getEffectiveRenderDistance());
        env.addProperty("world", Benchmark.WORLD_NAME);
        env.addProperty("seed", Benchmark.SEED);
        env.addProperty("warmupSeconds", Benchmark.WARMUP_SECONDS);
        env.addProperty("durationSeconds", Benchmark.DURATION_SECONDS);
        return env;
    }

    private static long percentile(final long[] sorted, final double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        return sorted[Math.clamp(index, 0, sorted.length - 1)];
    }

    private static double ms(final long nanos) {
        return round(nanos / 1.0e6, 3);
    }

    private static double round(final double value, final int places) {
        double scale = Math.pow(10, places);
        return Math.round(value * scale) / scale;
    }

    private static String sanitize(final String label) {
        return label.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
