package com.metallum.mixin.meteor;

import com.metallum.render.GlobalScissor;
import com.mojang.blaze3d.systems.RenderPassBackend;
import meteordevelopment.meteorclient.mixininterface.IGpuDevice;
import org.spongepowered.asm.mixin.Mixin;

/** Meteor Client casts the active GPU backend to {@link IGpuDevice} to clip its GUI; it only patches GL and Vulkan. */
@Mixin(targets = "com.metallum.render.MetalDevice", remap = false)
public abstract class MetalDeviceMeteorMixin implements IGpuDevice {
    @Override
    public void meteor$pushScissor(final int x, final int y, final int width, final int height) {
        GlobalScissor.push(x, y, width, height);
    }

    @Override
    public void meteor$popScissor() {
        GlobalScissor.pop();
    }

    @Override
    public void meteor$onCreateRenderPass(final RenderPassBackend pass) {
        GlobalScissor.apply(pass);
    }
}
