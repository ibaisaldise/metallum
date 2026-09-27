package com.metallum.mtl;

import com.metallum.bench.Counters;
import com.metallum.objc.AutoreleasePool;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.Semaphore;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class CAMetalLayer {
    private static final MemorySegment CLS = ObjC.clazz("CAMetalLayer");
    private static final Msg NEW = Msg.of("new", ADDRESS);
    private static final Msg SET_DEVICE = Msg.ofVoid("setDevice:", ADDRESS);
    private static final Msg SET_FRAMEBUFFER_ONLY = Msg.ofVoid("setFramebufferOnly:", JAVA_BOOLEAN);
    private static final Msg SET_OPAQUE = Msg.ofVoid("setOpaque:", JAVA_BOOLEAN);
    private static final Msg SET_CONTENTS_SCALE = Msg.ofVoid("setContentsScale:", JAVA_DOUBLE);
    private static final Msg SET_PIXEL_FORMAT = Msg.ofVoid("setPixelFormat:", JAVA_LONG);
    private static final Msg SET_DRAWABLE_SIZE = Msg.ofVoid("setDrawableSize:", JAVA_DOUBLE, JAVA_DOUBLE);
    private static final Msg SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT = Msg.ofVoid("setAllowsNextDrawableTimeout:", JAVA_BOOLEAN);
    private static final Msg SET_PRESENTS_WITH_TRANSACTION = Msg.ofVoid("setPresentsWithTransaction:", JAVA_BOOLEAN);
    private static final Msg SET_DISPLAY_SYNC_ENABLED = Msg.ofVoid("setDisplaySyncEnabled:", JAVA_BOOLEAN);
    private static final Msg NEXT_DRAWABLE = Msg.of("nextDrawable", true, ADDRESS);
    private static final Msg TEXTURE = Msg.of("texture", ADDRESS);
    private static final Msg COMMAND_BUFFER = Msg.of("commandBuffer", ADDRESS);
    private static final Msg RENDER_COMMAND_ENCODER = Msg.of("renderCommandEncoderWithDescriptor:", ADDRESS, ADDRESS);
    private static final Msg END_ENCODING = Msg.ofVoid("endEncoding");
    private static final Msg COMMIT = Msg.ofVoid("commit");
    private static final Msg WAIT_UNTIL_COMPLETED = Msg.ofVoid("waitUntilCompleted", true);

    private final MemorySegment handle;
    private final MTLCommandQueue fetchQueue;

    // A daemon thread blocks in nextDrawable and parks one retained drawable in `ready`. Even with display sync
    // off, the compositor only hands drawables back about once per refresh, so acquiring on the render thread
    // caps the frame rate at a small multiple of it. Off the render thread, immediate mode just skips presenting
    // when nothing is ready; the compositor shows the newest frame either way.
    private final Semaphore slotFree = new Semaphore(1);
    private final Semaphore slotFilled = new Semaphore(0);
    private volatile MemorySegment ready = MemorySegment.NULL;
    private volatile boolean immediatePresentMode;
    private @Nullable Thread fetcher;

    public CAMetalLayer(final MTLDevice device, final double contentsScale) {
        this.handle = NEW.sendPtr(CLS);
        if (ObjC.isNil(this.handle)) {
            throw new IllegalStateException("Failed to create CAMetalLayer");
        }
        SET_DEVICE.send(this.handle, device.handle());
        SET_FRAMEBUFFER_ONLY.send(this.handle, true);
        SET_OPAQUE.send(this.handle, true);
        SET_CONTENTS_SCALE.send(this.handle, contentsScale);
        this.fetchQueue = device.newCommandQueue();
    }

    public MemorySegment handle() {
        return this.handle;
    }

    public void configure(final double width, final double height, final boolean immediatePresentMode) {
        SET_PIXEL_FORMAT.send(this.handle, MTLPixelFormat.BGRA8Unorm.value);
        SET_DRAWABLE_SIZE.send(this.handle, width, height);
        SET_ALLOWS_NEXT_DRAWABLE_TIMEOUT.send(this.handle, false);
        SET_PRESENTS_WITH_TRANSACTION.send(this.handle, false);
        SET_DISPLAY_SYNC_ENABLED.send(this.handle, !immediatePresentMode);
        this.immediatePresentMode = immediatePresentMode;
        // A drawable fetched before a resize has the old size; hand it back and let the fetcher get a new one.
        if (this.slotFilled.tryAcquire()) {
            ObjC.release(takeReady());
        }
        if (this.fetcher == null) {
            this.fetcher = new Thread(this::fetchDrawables, "Metallum drawable fetcher");
            this.fetcher.setDaemon(true);
            this.fetcher.start();
        }
    }

    /**
     * Returns a retained drawable the caller must release, or null when this frame should not be presented.
     * Blocks for one with display sync on; returns null in immediate mode when none is free yet.
     */
    @Nullable
    CAMetalDrawable nextDrawable() {
        if (this.immediatePresentMode) {
            if (!this.slotFilled.tryAcquire()) {
                Counters.presentsSkipped++;
                return null;
            }
        } else {
            long start = System.nanoTime();
            this.slotFilled.acquireUninterruptibly();
            Counters.drawableWaitNanos += System.nanoTime() - start;
        }
        MemorySegment drawable = takeReady();
        return ObjC.isNil(drawable) ? null : new CAMetalDrawable(drawable);
    }

    private MemorySegment takeReady() {
        MemorySegment drawable = this.ready;
        this.ready = MemorySegment.NULL;
        this.slotFree.release();
        return drawable;
    }

    /**
     * nextDrawable can return a drawable the display is still reading; the first command buffer that writes it
     * then stalls on the GPU, holding up the render queue behind it. Take that stall here, on our own queue.
     */
    private void waitUntilWritable(final MemorySegment drawable) {
        MemorySegment commandBuffer = COMMAND_BUFFER.sendPtr(this.fetchQueue.handle());
        try (MTLRenderPassDescriptor renderPass = new MTLRenderPassDescriptor()) {
            renderPass.colorAttachment(
                    0,
                    TEXTURE.sendPtr(drawable),
                    MTLRenderPassDescriptor.LOAD_ACTION_DONT_CARE,
                    MTLRenderPassDescriptor.STORE_ACTION_STORE,
                    null
            );
            END_ENCODING.send(RENDER_COMMAND_ENCODER.sendPtr(commandBuffer, renderPass.handle()));
        }
        COMMIT.send(commandBuffer);
        WAIT_UNTIL_COMPLETED.send(commandBuffer);
    }

    private void fetchDrawables() {
        while (true) {
            this.slotFree.acquireUninterruptibly();
            try (AutoreleasePool _ = AutoreleasePool.push()) {
                MemorySegment drawable = NEXT_DRAWABLE.sendPtr(this.handle);
                if (!ObjC.isNil(drawable)) {
                    waitUntilWritable(drawable);
                }
                this.ready = ObjC.isNil(drawable) ? MemorySegment.NULL : ObjC.retain(drawable);
            }
            this.slotFilled.release();
        }
    }
}
