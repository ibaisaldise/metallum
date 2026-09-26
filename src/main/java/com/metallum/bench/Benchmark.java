package com.metallum.bench;

import com.metallum.Metallum;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Automated benchmark, enabled with {@code -Dmetallum.bench=true} (see the {@code runBenchmark} Gradle task).
 * <p>
 * Opens (or creates) a fixed-seed world, freezes it, warms up while spinning the camera once so every
 * direction is loaded, then records frame times and backend counters for one more spin and quits.
 * Results are written to {@code <gameDir>/metallum-bench/}.
 */
public final class Benchmark {
    public static final boolean ENABLED = Boolean.getBoolean("metallum.bench");

    static final String WORLD_NAME = System.getProperty("metallum.bench.world", "metallum-bench");
    static final long SEED = Long.getLong("metallum.bench.seed", 20260926L);
    static final int RENDER_DISTANCE = Integer.getInteger("metallum.bench.renderDistance", 16);
    static final int WARMUP_SECONDS = Integer.getInteger("metallum.bench.warmup", 20);
    static final int DURATION_SECONDS = Integer.getInteger("metallum.bench.duration", 30);
    private static final float PITCH = 0.0F;
    private static final long TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(5);

    private enum Phase { BOOT, LOADING, WARMUP, RECORD, SCREENSHOT, DONE }

    private static Phase phase = Phase.BOOT;
    private static long bootedAt = -1L;
    private static long phaseStartedAt;
    private static float startYaw;
    private static final FrameRecorder RECORDER = new FrameRecorder();
    private static final int SCREENSHOT_SETTLE_FRAMES = 10;
    private static final long SCREENSHOT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    @Nullable
    private static Path resultsFile;
    private static int screenshotFrames;
    private static volatile boolean screenshotDone;

    private Benchmark() {
    }

    /** Called at the start of every client frame. */
    public static void onFrame(final Minecraft mc) {
        long now = System.nanoTime();
        if (bootedAt < 0L) {
            bootedAt = now;
            // Before the title screen can start music: no audio work during the benchmark.
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        }
        if ((phase == Phase.BOOT || phase == Phase.LOADING || phase == Phase.WARMUP) && now - bootedAt > TIMEOUT_NANOS) {
            abort(mc, "timed out in phase " + phase);
            return;
        }

        switch (phase) {
            case BOOT -> {
                // Wait for resource loading to finish and the first screen (title or onboarding) to show.
                if (mc.gui.overlay() == null && mc.gui.screen() != null) {
                    applyOptions(mc.options);
                    openWorld(mc);
                    phase = Phase.LOADING;
                }
            }
            case LOADING -> {
                if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
                    mc.player.getAbilities().flying = true;
                    mc.player.onUpdateAbilities();
                    startYaw = mc.player.getYRot();
                    runCommand(mc, "tick unfreeze");
                    runCommand(mc, "time set noon");
                    runCommand(mc, "weather clear");
                    Metallum.LOGGER.info("[metallum-bench] world loaded, warming up for {}s", WARMUP_SECONDS);
                    enterPhase(Phase.WARMUP, now);
                }
            }
            case WARMUP -> {
                if (!spin(mc, now, WARMUP_SECONDS)) {
                    return;
                }
                if (now - phaseStartedAt >= TimeUnit.SECONDS.toNanos(WARMUP_SECONDS)) {
                    runCommand(mc, "time set noon");
                    runCommand(mc, "tick freeze");
                    // scripts/bench.py profile watches for this line to attach a profiler to the recorded window.
                    Metallum.LOGGER.info("[metallum-bench] recording for {}s (pid {})", DURATION_SECONDS, ProcessHandle.current().pid());
                    enterPhase(Phase.RECORD, now);
                    RECORDER.start(now);
                }
            }
            case RECORD -> {
                RECORDER.frame(now);
                if (!spin(mc, now, DURATION_SECONDS)) {
                    return;
                }
                if (now - phaseStartedAt >= TimeUnit.SECONDS.toNanos(DURATION_SECONDS)) {
                    resultsFile = RECORDER.writeResults(mc, null);
                    // Back at the starting angle with the HUD hidden: the frozen world should render
                    // identically on every run, so scripts/bench.py can diff it against the baseline.
                    mc.player.absSnapRotationTo(startYaw, PITCH);
                    if (!mc.gui.hud.isHidden()) {
                        mc.gui.hud.toggle();
                    }
                    enterPhase(Phase.SCREENSHOT, now);
                }
            }
            case SCREENSHOT -> {
                if (resultsFile == null || screenshotDone || now - phaseStartedAt > SCREENSHOT_TIMEOUT_NANOS) {
                    phase = Phase.DONE;
                    mc.stop();
                } else if (++screenshotFrames == SCREENSHOT_SETTLE_FRAMES) {
                    Path png = resultsFile.resolveSibling(resultsFile.getFileName().toString().replace(".json", ".png"));
                    Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                        try (image) {
                            image.writeToFile(png);
                        } catch (IOException e) {
                            Metallum.LOGGER.error("[metallum-bench] failed to write screenshot", e);
                        }
                        screenshotDone = true;
                    });
                }
            }
            case DONE -> {
            }
        }
    }

    private static void enterPhase(final Phase next, final long now) {
        phase = next;
        phaseStartedAt = now;
    }

    /** One full revolution over {@code periodSeconds}. Returns false (and aborts) if the player went away. */
    private static boolean spin(final Minecraft mc, final long now, final int periodSeconds) {
        if (mc.player == null) {
            abort(mc, "player disappeared during " + phase);
            return false;
        }
        double t = (double) (now - phaseStartedAt) / TimeUnit.SECONDS.toNanos(periodSeconds);
        mc.player.absSnapRotationTo(startYaw + (float) (360.0 * Math.min(t, 1.0)), PITCH);
        return true;
    }

    private static void applyOptions(final Options options) {
        options.enableVsync().set(false);
        options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
        options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        options.renderDistance().set(RENDER_DISTANCE);
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
    }

    private static void openWorld(final Minecraft mc) {
        if (mc.getLevelSource().levelExists(WORLD_NAME)) {
            Metallum.LOGGER.info("[metallum-bench] opening existing world '{}'", WORLD_NAME);
            mc.createWorldOpenFlows().openWorld(WORLD_NAME, () -> abort(mc, "failed to open world " + WORLD_NAME));
            return;
        }
        Metallum.LOGGER.info("[metallum-bench] creating world '{}' with seed {}", WORLD_NAME, SEED);
        LevelSettings settings = new LevelSettings(
                WORLD_NAME,
                GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
                true,
                WorldDataConfiguration.DEFAULT
        );
        mc.createWorldOpenFlows().createFreshLevel(
                WORLD_NAME,
                settings,
                new WorldOptions(SEED, true, false),
                WorldPresets::createNormalWorldDimensions,
                mc.gui.screen()
        );
    }

    private static void runCommand(final Minecraft mc, final String command) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), command));
    }

    private static void abort(final Minecraft mc, final String reason) {
        Metallum.LOGGER.error("[metallum-bench] aborting: {}", reason);
        phase = Phase.DONE;
        RECORDER.writeResults(mc, reason);
        mc.stop();
    }
}
