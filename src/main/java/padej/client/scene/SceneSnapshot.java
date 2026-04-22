package padej.client.scene;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class SceneSnapshot {
    private final String sceneName;
    private final String dimensionId;
    private final int centerBlockX;
    private final int centerBlockY;
    private final int centerBlockZ;
    private final float captureYaw;
    private final List<SceneBlock> blocks;
    private final List<LightSample> lightSamples;

    public SceneSnapshot(
            String sceneName,
            String dimensionId,
            int centerBlockX,
            int centerBlockY,
            int centerBlockZ,
            float captureYaw,
            List<SceneBlock> blocks,
            List<LightSample> lightSamples
    ) {
        this.sceneName = sceneName;
        this.dimensionId = dimensionId;
        this.centerBlockX = centerBlockX;
        this.centerBlockY = centerBlockY;
        this.centerBlockZ = centerBlockZ;
        this.captureYaw = captureYaw;
        this.blocks = List.copyOf(blocks);
        this.lightSamples = List.copyOf(lightSamples);
    }

    public String sceneName() {
        return sceneName;
    }

    public String dimensionId() {
        return dimensionId;
    }

    public int centerBlockX() {
        return centerBlockX;
    }

    public int centerBlockY() {
        return centerBlockY;
    }

    public int centerBlockZ() {
        return centerBlockZ;
    }

    public float captureYaw() {
        return captureYaw;
    }

    public List<SceneBlock> blocks() {
        return blocks;
    }

    public List<LightSample> lightSamples() {
        return lightSamples;
    }

    public record SceneBlock(
            int relX,
            int relY,
            int relZ,
            BlockState state,
            int packedLight,
            @Nullable NbtCompound blockEntityNbt
    ) {
        public SceneBlock {
            blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.copy();
        }
    }

    public record LightSample(
            int relX,
            int relY,
            int relZ,
            int packedLight
    ) {
    }
}
