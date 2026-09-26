package com.metallum.mixin.bench;

import com.metallum.bench.Benchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftBenchmarkMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void metallum$benchmarkFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        Benchmark.onFrame((Minecraft) (Object) this);
    }
}
