package padej.client.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalRenderBlock;

import java.util.HashMap;
import java.util.Map;

public final class SceneBlockRenderView implements BlockRenderView {
    private static final BlockState AIR = Blocks.AIR.getDefaultState();

    private final ClientWorld world;
    private final Map<Long, BlockState> statesByPos;
    private final Map<Long, Integer> packedLightByPos;

    private SceneBlockRenderView(
            ClientWorld world,
            Map<Long, BlockState> statesByPos,
            Map<Long, Integer> packedLightByPos
    ) {
        this.world = world;
        this.statesByPos = statesByPos;
        this.packedLightByPos = packedLightByPos;
    }

    public static SceneBlockRenderView fromPortal(MinecraftClient client, PortalInstance portal) {
        ClientWorld world = client.world;
        if (world == null) {
            throw new IllegalStateException("Client world is required for scene rendering");
        }

        Map<Long, BlockState> states = new HashMap<>(Math.max(16, portal.renderBlocks().size() * 2));
        Map<Long, Integer> lights = new HashMap<>(Math.max(16, portal.renderBlocks().size() * 2));
        for (PortalRenderBlock block : portal.renderBlocks()) {
            BlockPos localPos = BlockPos.ofFloored(block.localBlockPosition());
            long key = BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ());
            states.put(key, block.state());
            lights.put(key, block.packedLight());
        }

        return new SceneBlockRenderView(world, states, lights);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return statesByPos.getOrDefault(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()), AIR);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public int getHeight() {
        return world.getHeight();
    }

    @Override
    public int getBottomY() {
        return world.getBottomY();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        if (!shaded) {
            return 1.0F;
        }
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public LightingProvider getLightingProvider() {
        return world.getChunkManager().getLightingProvider();
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return world.getColor(pos, colorResolver);
    }

    @Override
    public int getLightLevel(LightType type, BlockPos pos) {
        long key = BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ());
        Integer packed = packedLightByPos.get(key);
        if (packed == null) {
            return type == LightType.SKY ? 15 : 0;
        }

        int blockLight = (packed >> 4) & 0xF;
        int skyLight = (packed >> 20) & 0xF;

        if (type == LightType.BLOCK) {
            return Math.max(blockLight, getBlockState(pos).getLuminance());
        }
        return skyLight;
    }
}
