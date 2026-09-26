package com.metallum.mtl;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

@Environment(EnvType.CLIENT)
public final class MTLScissorRect {
    private MTLScissorRect() {
    }

    static MemorySegment write(final MemorySegment rect, final long x, final long y, final long width, final long height) {
        rect.set(JAVA_LONG, 0, x);
        rect.set(JAVA_LONG, 8, y);
        rect.set(JAVA_LONG, 16, width);
        rect.set(JAVA_LONG, 24, height);
        return rect;
    }
}
