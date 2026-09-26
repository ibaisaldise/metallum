package com.metallum.mtl;

import com.metallum.bench.Counters;
import com.metallum.objc.Msg;
import com.metallum.objc.ObjC;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.*;

@Environment(EnvType.CLIENT)
public final class MTLRenderCommandEncoder extends MTLCommandEncoder {
    private static final Msg SET_RENDER_PIPELINE_STATE = Msg.ofVoid("setRenderPipelineState:", ADDRESS);
    private static final Msg SET_DEPTH_STENCIL_STATE = Msg.ofVoid("setDepthStencilState:", ADDRESS);
    private static final Msg SET_DEPTH_BIAS = Msg.ofVoid("setDepthBias:slopeScale:clamp:", JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT);
    private static final Msg SET_FRONT_FACING_WINDING = Msg.ofVoid("setFrontFacingWinding:", JAVA_LONG);
    private static final Msg SET_CULL_MODE = Msg.ofVoid("setCullMode:", JAVA_LONG);
    private static final Msg SET_TRIANGLE_FILL_MODE = Msg.ofVoid("setTriangleFillMode:", JAVA_LONG);
    private static final Msg SET_VERTEX_BUFFER = Msg.ofVoid("setVertexBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER = Msg.ofVoid("setFragmentBuffer:offset:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_BUFFER_OFFSET = Msg.ofVoid("setVertexBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_FRAGMENT_BUFFER_OFFSET = Msg.ofVoid("setFragmentBufferOffset:atIndex:", JAVA_LONG, JAVA_LONG);
    private static final Msg SET_VERTEX_TEXTURE = Msg.ofVoid("setVertexTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_TEXTURE = Msg.ofVoid("setFragmentTexture:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_VERTEX_SAMPLER = Msg.ofVoid("setVertexSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_FRAGMENT_SAMPLER = Msg.ofVoid("setFragmentSamplerState:atIndex:", ADDRESS, JAVA_LONG);
    private static final Msg SET_SCISSOR_RECT = Msg.ofVoid("setScissorRect:", ADDRESS);
    private static final Msg SET_VIEWPORT = Msg.ofVoid("setViewport:", ADDRESS);
    private static final Msg SET_VERTEX_BYTES = Msg.ofVoid("setVertexBytes:length:atIndex:", ADDRESS, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_PRIMITIVES = Msg.ofVoid("drawPrimitives:vertexStart:vertexCount:instanceCount:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED = Msg.ofVoid("drawIndexedPrimitives:indexCount:indexType:indexBuffer:indexBufferOffset:instanceCount:baseVertex:baseInstance:",
            JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final Msg DRAW_INDEXED_INDIRECT = Msg.ofVoid("drawIndexedPrimitives:indexType:indexBuffer:indexBufferOffset:indirectBuffer:indirectBufferOffset:",
            JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg DRAW_INDIRECT = Msg.ofVoid("drawPrimitives:indirectBuffer:indirectBufferOffset:", JAVA_LONG, ADDRESS, JAVA_LONG);
    private static final Msg UPDATE_FENCE = Msg.ofVoid("updateFence:afterStages:", ADDRESS, JAVA_LONG);
    private static final Msg WAIT_FOR_FENCE = Msg.ofVoid("waitForFence:beforeStages:", ADDRESS, JAVA_LONG);

    // Shadow of the encoder's bound state, so redundant set* calls never cross into native code.
    // Handles are compared by address: every resource is released through the deferred destruction
    // queue, so an address cannot be reused while this encoder is still recording.
    private static final int TRACKED_BUFFERS = 31;
    private static final int TRACKED_TEXTURES = 32;
    private static final int TRACKED_SAMPLERS = 16;
    private static final long UNKNOWN = -1L;

    private final long[] vertexBuffers = unknown(TRACKED_BUFFERS);
    private final long[] vertexBufferOffsets = new long[TRACKED_BUFFERS];
    private final long[] fragmentBuffers = unknown(TRACKED_BUFFERS);
    private final long[] fragmentBufferOffsets = new long[TRACKED_BUFFERS];
    private final long[] vertexTextures = unknown(TRACKED_TEXTURES);
    private final long[] fragmentTextures = unknown(TRACKED_TEXTURES);
    private final long[] vertexSamplers = unknown(TRACKED_SAMPLERS);
    private final long[] fragmentSamplers = unknown(TRACKED_SAMPLERS);
    private long pipeline = UNKNOWN;
    private long depthStencil = UNKNOWN;
    private boolean depthBiasSet;
    private float depthBias;
    private float depthSlopeScale;
    private float depthBiasClamp;
    private long winding = UNKNOWN;
    private long cullMode = UNKNOWN;
    private long fillMode = UNKNOWN;
    private boolean scissorSet;
    private long scissorX;
    private long scissorY;
    private long scissorWidth;
    private long scissorHeight;
    private boolean viewportSet;
    private final double[] viewport = new double[6];
    // Scratch structs for by-reference arguments. All encoding happens on the render thread.
    private static final MemorySegment SCISSOR_RECT = Arena.global().allocate(32, 8);
    private static final MemorySegment VIEWPORT = Arena.global().allocate(48, 8);

    MTLRenderCommandEncoder(final MemorySegment handle) {
        super(handle);
    }

    public void setRenderPipelineState(final MemorySegment pipeline) {
        MemorySegment value = ObjC.orNil(pipeline);
        if (this.pipeline == value.address()) {
            return;
        }
        this.pipeline = value.address();
        Counters.pipelineBinds++;
        SET_RENDER_PIPELINE_STATE.send(handle(), value);
    }

    public void setDepthStencilState(final MemorySegment depthStencilState) {
        MemorySegment value = ObjC.orNil(depthStencilState);
        if (depthStencil == value.address()) {
            return;
        }
        depthStencil = value.address();
        SET_DEPTH_STENCIL_STATE.send(handle(), value);
    }

    public void setDepthBias(final float depthBias, final float slopeScale, final float clamp) {
        if (depthBiasSet && this.depthBias == depthBias && depthSlopeScale == slopeScale && depthBiasClamp == clamp) {
            return;
        }
        depthBiasSet = true;
        this.depthBias = depthBias;
        depthSlopeScale = slopeScale;
        depthBiasClamp = clamp;
        SET_DEPTH_BIAS.send(handle(), depthBias, slopeScale, clamp);
    }

    public void setFrontFacingWinding(final MTLWinding winding) {
        if (this.winding == winding.value) {
            return;
        }
        this.winding = winding.value;
        SET_FRONT_FACING_WINDING.send(handle(), winding.value);
    }

    public void setCullMode(final MTLCullMode cullMode) {
        if (this.cullMode == cullMode.value) {
            return;
        }
        this.cullMode = cullMode.value;
        SET_CULL_MODE.send(handle(), cullMode.value);
    }

    public void setTriangleFillMode(final MTLTriangleFillMode fillMode) {
        if (this.fillMode == fillMode.value) {
            return;
        }
        this.fillMode = fillMode.value;
        SET_TRIANGLE_FILL_MODE.send(handle(), fillMode.value);
    }

    public void setVertexBuffer(final MTLBuffer buffer, final long offset, final long index) {
        MemorySegment value = seg(buffer);
        if (index < TRACKED_BUFFERS) {
            int slot = (int) index;
            if (vertexBuffers[slot] == value.address() && value.address() != 0L) {
                setVertexBufferOffset(offset, index);
                return;
            }
            vertexBuffers[slot] = value.address();
            vertexBufferOffsets[slot] = offset;
        }
        Counters.bufferBinds++;
        SET_VERTEX_BUFFER.send(handle(), value, offset, index);
    }

    public void setFragmentBuffer(final MTLBuffer buffer, final long offset, final long index) {
        MemorySegment value = seg(buffer);
        if (index < TRACKED_BUFFERS) {
            int slot = (int) index;
            if (fragmentBuffers[slot] == value.address() && value.address() != 0L) {
                setFragmentBufferOffset(offset, index);
                return;
            }
            fragmentBuffers[slot] = value.address();
            fragmentBufferOffsets[slot] = offset;
        }
        Counters.bufferBinds++;
        SET_FRAGMENT_BUFFER.send(handle(), value, offset, index);
    }

    public void setVertexBufferOffset(final long offset, final long index) {
        if (index < TRACKED_BUFFERS) {
            if (vertexBufferOffsets[(int) index] == offset) {
                return;
            }
            vertexBufferOffsets[(int) index] = offset;
        }
        Counters.bufferBinds++;
        SET_VERTEX_BUFFER_OFFSET.send(handle(), offset, index);
    }

    public void setFragmentBufferOffset(final long offset, final long index) {
        if (index < TRACKED_BUFFERS) {
            if (fragmentBufferOffsets[(int) index] == offset) {
                return;
            }
            fragmentBufferOffsets[(int) index] = offset;
        }
        Counters.bufferBinds++;
        SET_FRAGMENT_BUFFER_OFFSET.send(handle(), offset, index);
    }

    public void setVertexTexture(final MemorySegment texture, final long index) {
        MemorySegment value = ObjC.orNil(texture);
        if (!changed(vertexTextures, index, value.address())) {
            return;
        }
        Counters.textureBinds++;
        SET_VERTEX_TEXTURE.send(handle(), value, index);
    }

    public void setFragmentTexture(final MemorySegment texture, final long index) {
        MemorySegment value = ObjC.orNil(texture);
        if (!changed(fragmentTextures, index, value.address())) {
            return;
        }
        Counters.textureBinds++;
        SET_FRAGMENT_TEXTURE.send(handle(), value, index);
    }

    public void setVertexSamplerState(final MemorySegment sampler, final long index) {
        MemorySegment value = ObjC.orNil(sampler);
        if (!changed(vertexSamplers, index, value.address())) {
            return;
        }
        Counters.samplerBinds++;
        SET_VERTEX_SAMPLER.send(handle(), value, index);
    }

    public void setFragmentSamplerState(final MemorySegment sampler, final long index) {
        MemorySegment value = ObjC.orNil(sampler);
        if (!changed(fragmentSamplers, index, value.address())) {
            return;
        }
        Counters.samplerBinds++;
        SET_FRAGMENT_SAMPLER.send(handle(), value, index);
    }

    public void setScissorRect(final long x, final long y, final long width, final long height) {
        if (scissorSet && scissorX == x && scissorY == y && scissorWidth == width && scissorHeight == height) {
            return;
        }
        scissorSet = true;
        scissorX = x;
        scissorY = y;
        scissorWidth = width;
        scissorHeight = height;
        SET_SCISSOR_RECT.send(handle(), MTLScissorRect.write(SCISSOR_RECT, x, y, width, height));
    }

    public void setViewport(final double originX, final double originY, final double width, final double height, final double znear, final double zfar) {
        double[] v = viewport;
        if (viewportSet && v[0] == originX && v[1] == originY && v[2] == width && v[3] == height && v[4] == znear && v[5] == zfar) {
            return;
        }
        viewportSet = true;
        v[0] = originX;
        v[1] = originY;
        v[2] = width;
        v[3] = height;
        v[4] = znear;
        v[5] = zfar;
        for (int i = 0; i < 6; i++) {
            VIEWPORT.set(JAVA_DOUBLE, i * 8L, v[i]);
        }
        SET_VIEWPORT.send(handle(), VIEWPORT);
    }

    public void setVertexBytes(final MemorySegment bytes, final long length, final long index) {
        if (index < TRACKED_BUFFERS) {
            vertexBuffers[(int) index] = UNKNOWN;
        }
        SET_VERTEX_BYTES.send(handle(), bytes, length, index);
    }

    public void clearDraw(
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            @Nullable final Vector4fc clearColor,
            @Nullable final Double clearDepth
    ) {
        MTLBuiltinPipelines.clearDraw(
                this,
                colorTexture,
                depthTexture,
                viewportWidth,
                viewportHeight,
                clearColor,
                clearDepth
        );
    }

    public void drawPrimitives(final MTLPrimitiveType primitiveType, final int firstVertex, final int vertexCount, final int instanceCount, final int baseInstance) {
        Counters.drawCalls++;
        DRAW_PRIMITIVES.send(handle(), primitiveType.value, firstVertex, vertexCount, instanceCount, baseInstance);
    }

    public void drawIndexedPrimitives(final MTLPrimitiveType primitiveType, final int indexCount, final MTLIndexType indexType, final MTLBuffer indexBuffer, final long offset, final int instanceCount, final int baseVertex, final int baseInstance) {
        Counters.drawCalls++;
        DRAW_INDEXED.send(handle(), primitiveType.value, indexCount, indexType.value, indexBuffer.handle(), offset, instanceCount, baseVertex, baseInstance);
    }

    public void drawIndexedPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLIndexType indexType, final MTLBuffer indexBuffer, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        Counters.drawCalls++;
        Counters.indirectDraws++;
        DRAW_INDEXED_INDIRECT.send(handle(), primitiveType.value, indexType.value, indexBuffer.handle(), 0L, indirectBuffer.handle(), indirectBufferOffset);
    }

    public void drawPrimitivesIndirect(final MTLPrimitiveType primitiveType, final MTLBuffer indirectBuffer, final long indirectBufferOffset) {
        Counters.drawCalls++;
        Counters.indirectDraws++;
        DRAW_INDIRECT.send(handle(), primitiveType.value, indirectBuffer.handle(), indirectBufferOffset);
    }

    public void updateFence(final MTLFence fence, final MTLRenderStages stages) {
        UPDATE_FENCE.send(handle(), fence.handle(), stages.value);
    }

    public void waitForFence(final MTLFence fence, final MTLRenderStages stages) {
        WAIT_FOR_FENCE.send(handle(), fence.handle(), stages.value);
    }

    private static boolean changed(final long[] shadow, final long index, final long address) {
        if (index >= shadow.length) {
            return true;
        }
        if (shadow[(int) index] == address) {
            return false;
        }
        shadow[(int) index] = address;
        return true;
    }

    private static long[] unknown(final int size) {
        long[] values = new long[size];
        java.util.Arrays.fill(values, UNKNOWN);
        return values;
    }

    private static MemorySegment seg(final MTLBuffer buffer) {
        return buffer == null ? MemorySegment.NULL : buffer.handle();
    }
}
