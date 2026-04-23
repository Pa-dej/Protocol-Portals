package padej.client.render;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import padej.client.portal.PortalLightSample;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalRenderBlock;

public final class SceneBlockRenderView implements BlockRenderView {
    private static final BlockState AIR = Blocks.AIR.getDefaultState();
    private static final int ABSENT_PACKED_LIGHT = -1;

    private final ClientWorld world;
    private final Long2ObjectOpenHashMap<BlockState> statesByPos;
    private final Long2IntOpenHashMap packedLightByPos;

    private SceneBlockRenderView(
            ClientWorld world,
            Long2ObjectOpenHashMap<BlockState> statesByPos,
            Long2IntOpenHashMap packedLightByPos
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

        int stateCapacity = Math.max(16, portal.renderBlocks().size());
        int lightCapacity = Math.max(16, portal.renderBlocks().size() + portal.lightSamples().size());
        Long2ObjectOpenHashMap<BlockState> states = new Long2ObjectOpenHashMap<>(stateCapacity);
        Long2IntOpenHashMap lights = new Long2IntOpenHashMap(lightCapacity);
        lights.defaultReturnValue(ABSENT_PACKED_LIGHT);
        for (PortalRenderBlock block : portal.renderBlocks()) {
            BlockPos localPos = BlockPos.ofFloored(block.localBlockPosition());
            long key = BlockPos.asLong(localPos.getX(), localPos.getY(), localPos.getZ());
            states.put(key, block.state());
            mergePackedLight(lights, key, block.packedLight());
        }

        for (PortalLightSample sample : portal.lightSamples()) {
            long key = BlockPos.asLong(sample.relX(), sample.relY(), sample.relZ());
            mergePackedLight(lights, key, sample.packedLight());
        }

        return new SceneBlockRenderView(world, states, lights);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = statesByPos.get(BlockPos.asLong(pos.getX(), pos.getY(), pos.getZ()));
        return state == null ? AIR : state;
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
        int packed = packedLightByPos.get(key);
        if (packed == ABSENT_PACKED_LIGHT) {
            return type == LightType.SKY ? 15 : 0;
        }

        int blockLight = (packed >> 4) & 0xF;
        int skyLight = (packed >> 20) & 0xF;

        if (type == LightType.BLOCK) {
            return Math.max(blockLight, getBlockState(pos).getLuminance());
        }
        return skyLight;
    }

    private static void mergePackedLight(Long2IntOpenHashMap lightsByPos, long key, int packedLight) {
        int existingPackedLight = lightsByPos.get(key);
        if (existingPackedLight == ABSENT_PACKED_LIGHT) {
            lightsByPos.put(key, packedLight);
            return;
        }
        lightsByPos.put(key, maxPackedLight(existingPackedLight, packedLight));
    }

    private static int maxPackedLight(int a, int b) {
        int blockA = (a >> 4) & 0xF;
        int blockB = (b >> 4) & 0xF;
        int skyA = (a >> 20) & 0xF;
        int skyB = (b >> 20) & 0xF;
        return ((Math.max(skyA, skyB) & 0xF) << 20) | ((Math.max(blockA, blockB) & 0xF) << 4);
    }
}
