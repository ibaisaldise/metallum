package meteordevelopment.meteorclient.mixininterface;

import com.mojang.blaze3d.systems.RenderPassBackend;

/** Compile-time copy of Meteor Client's interface, excluded from the jar; the real one comes from Meteor at runtime. */
public interface IGpuDevice {
    void meteor$pushScissor(int x, int y, int width, int height);

    void meteor$popScissor();

    void meteor$onCreateRenderPass(RenderPassBackend pass);
}
