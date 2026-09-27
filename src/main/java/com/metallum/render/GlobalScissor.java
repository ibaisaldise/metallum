package com.metallum.render;

import com.mojang.blaze3d.systems.RenderPassBackend;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * A scissor rect applied to every render pass created while it is pushed. Meteor Client clips its GUI this way,
 * mirroring what it adds to the vanilla OpenGL and Vulkan backends. Render thread only.
 */
@Environment(EnvType.CLIENT)
public final class GlobalScissor {
    private static boolean set;
    private static int x;
    private static int y;
    private static int width;
    private static int height;

    private GlobalScissor() {
    }

    public static void push(final int x, final int y, final int width, final int height) {
        if (set) {
            throw new IllegalStateException("Currently there can only be one global scissor pushed");
        }
        GlobalScissor.x = x;
        GlobalScissor.y = y;
        GlobalScissor.width = width;
        GlobalScissor.height = height;
        set = true;
    }

    public static void pop() {
        if (!set) {
            throw new IllegalStateException("No scissor pushed");
        }
        set = false;
    }

    public static void apply(final RenderPassBackend pass) {
        if (set) {
            pass.enableScissor(x, y, width, height);
        }
    }
}
