package com.koteinik.chunksfadein.mixin;

import java.nio.ByteBuffer;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;

import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.koteinik.chunksfadein.Config;
import com.koteinik.chunksfadein.extenstions.ChunkShaderInterfaceExt;
import com.koteinik.chunksfadein.extenstions.RenderRegionArenasExt;

import me.jellysquid.mods.sodium.client.gl.arena.staging.StagingBuffer;
import me.jellysquid.mods.sodium.client.gl.buffer.GlBufferUsage;
import me.jellysquid.mods.sodium.client.gl.buffer.GlMutableBuffer;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion.RenderRegionArenas;

@Mixin(value = RenderRegionArenas.class, remap = false)
public class RenderRegionArenasMixin implements RenderRegionArenasExt {
    private final boolean isEnabled = Config.needToTurnOff();

    private static final int FADE_COEFF_STRIDE = 4 * 4;

    private final ByteBuffer chunkFadeCoeffsBuffer = createFadeCoeffsBuffer();
    private GlMutableBuffer chunkGlFadeCoeffBuffer;

    private CommandList commandList;

    private HashSet<Integer> chunksToReset = new HashSet<>();

    private long lastFrameTime = 0L;

    @Inject(method = "<init>", at = @At(value = "RETURN"))
    private void modifyConstructor(CommandList commandList, StagingBuffer stagingBuffer, CallbackInfo ci) {
        if (isEnabled)
            return;

        chunkGlFadeCoeffBuffer = commandList.createMutableBuffer();
        this.commandList = commandList;
        uploadFadeCoeffDataToGl();
    }

    @Inject(method = "delete", at = @At(value = "TAIL"))
    private void modifyDelete(CommandList commandList, CallbackInfo ci) {
        if (isEnabled)
            return;

        chunksToReset.clear();
        chunkFadeCoeffsBuffer.clear();
        commandList.deleteBuffer(chunkGlFadeCoeffBuffer);
    }

    @Override
    public void resetFadeCoeffForChunk(RenderSection chunk) {
        chunksToReset.add(chunk.getChunkId());
    }

    @Override
    public void updateChunksFade(List<RenderSection> chunks, ChunkShaderInterfaceExt shader) {
        final long currentFrameTime = ZonedDateTime.now().toInstant().toEpochMilli();

        final float fadeCoeffChange = lastFrameTime == 0L ? 0
                : (currentFrameTime - lastFrameTime) * Config.fadeCoeffPerMs;

        for (RenderSection chunk : chunks)
            processChunk(fadeCoeffChange, chunk);

        uploadFadeCoeffDataToGl();
        shader.setFadeCoeffs(chunkGlFadeCoeffBuffer);
        lastFrameTime = currentFrameTime;
    }

    private void processChunk(final float fadeCoeffChange, RenderSection chunk) {
        final int chunkId = chunk.getChunkId();

        float fadeCoeff = chunkFadeCoeffsBuffer.getFloat(chunkId * FADE_COEFF_STRIDE);

        if (chunksToReset.remove(chunkId))
            fadeCoeff = 0f;
        else
            fadeCoeff += fadeCoeffChange;

        if (fadeCoeff == 1f)
            return;

        if (fadeCoeff > 1f)
            fadeCoeff = 1f;

        chunkFadeCoeffsBuffer.putFloat(chunkId * FADE_COEFF_STRIDE, fadeCoeff);
    }

    private void uploadFadeCoeffDataToGl() {
        commandList.uploadData(chunkGlFadeCoeffBuffer, chunkFadeCoeffsBuffer, GlBufferUsage.DYNAMIC_DRAW);
    }

    private ByteBuffer createFadeCoeffsBuffer() {
        ByteBuffer data = MemoryUtil.memAlloc(RenderRegion.REGION_SIZE * FADE_COEFF_STRIDE);

        for (int i = 0; i < RenderRegion.REGION_SIZE; i++)
            data.putFloat(i * FADE_COEFF_STRIDE, 0f);

        return data;
    }
}
