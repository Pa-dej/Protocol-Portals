package padej.client.scene;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public final class SceneRepository {
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String FILE_EXTENSION = ".nbt";
    private static final int SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS = 48;
    private static final int SNAPSHOT_VERTICAL_RADIUS_BLOCKS = 48;
    private static final int MAX_CAPTURED_BLOCKS = 180_000;

    private final Path scenesDir;
    private final Map<String, SceneSnapshot> cache = new HashMap<>();

    public record CaptureResult(
            String sceneName,
            int blockCount,
            boolean truncated,
            int horizontalRadius,
            int verticalRadius
    ) {
    }

    public SceneRepository() {
        this.scenesDir = FabricLoader.getInstance().getConfigDir().resolve("protocol-portals").resolve("scenes");
    }

    public Path scenesDir() {
        return scenesDir;
    }

    public boolean isValidFileName(String fileName) {
        return FILE_NAME_PATTERN.matcher(fileName).matches();
    }

    public Optional<SceneSnapshot> load(String fileName) {
        String normalized = normalize(fileName);
        if (cache.containsKey(normalized)) {
            return Optional.of(cache.get(normalized));
        }

        Path path = scenePath(normalized);
        if (!Files.exists(path)) {
            return Optional.empty();
        }

        try {
            SceneSnapshot snapshot = SceneSnapshotIO.read(path, normalized);
            cache.put(normalized, snapshot);
            return Optional.of(snapshot);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public CaptureResult captureAndSave(MinecraftClient client, String fileName) throws IOException {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            throw new IOException("Player/world is not available");
        }

        String normalized = normalize(fileName);
        Files.createDirectories(scenesDir);

        BlockPos center = player.getBlockPos();
        int centerX = center.getX();
        int centerY = center.getY();
        int centerZ = center.getZ();

        int minX = centerX - SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS;
        int maxX = centerX + SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS;
        int minZ = centerZ - SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS;
        int maxZ = centerZ + SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS;

        int minY = Math.max(world.getBottomY(), centerY - SNAPSHOT_VERTICAL_RADIUS_BLOCKS);
        int maxY = Math.min(world.getTopY() - 1, centerY + SNAPSHOT_VERTICAL_RADIUS_BLOCKS);

        int minChunkX = Math.floorDiv(minX, 16);
        int maxChunkX = Math.floorDiv(maxX, 16);
        int minChunkZ = Math.floorDiv(minZ, 16);
        int maxChunkZ = Math.floorDiv(maxZ, 16);

        List<SceneSnapshot.SceneBlock> blocks = new ArrayList<>();
        BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        boolean truncated = false;

        captureLoop:
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                if (chunk.isEmpty()) {
                    continue;
                }

                int chunkStartX = chunkX * 16;
                int chunkStartZ = chunkZ * 16;
                int startX = Math.max(minX, chunkStartX);
                int endX = Math.min(maxX, chunkStartX + 15);
                int startZ = Math.max(minZ, chunkStartZ);
                int endZ = Math.min(maxZ, chunkStartZ + 15);

                for (int y = minY; y <= maxY; y++) {
                    for (int worldX = startX; worldX <= endX; worldX++) {
                        int localX = worldX - chunkStartX;
                        int relX = worldX - centerX;
                        for (int worldZ = startZ; worldZ <= endZ; worldZ++) {
                            int localZ = worldZ - chunkStartZ;
                            mutablePos.set(localX, y, localZ);
                            BlockState state = chunk.getBlockState(mutablePos);
                            if (state.isAir()) {
                                continue;
                            }

                            int relY = y - centerY;
                            int relZ = worldZ - centerZ;
                            blocks.add(new SceneSnapshot.SceneBlock(relX, relY, relZ, state));
                            if (blocks.size() >= MAX_CAPTURED_BLOCKS) {
                                truncated = true;
                                break captureLoop;
                            }
                        }
                    }
                }
            }
        }

        SceneSnapshot snapshot = new SceneSnapshot(
                normalized,
                world.getRegistryKey().getValue().toString(),
                centerX,
                centerY,
                centerZ,
                player.getYaw(),
                blocks
        );

        SceneSnapshotIO.write(scenePath(normalized), snapshot);
        cache.remove(normalized);
        return new CaptureResult(
                normalized,
                blocks.size(),
                truncated,
                SNAPSHOT_HORIZONTAL_RADIUS_BLOCKS,
                SNAPSHOT_VERTICAL_RADIUS_BLOCKS
        );
    }

    public static Text invalidNameText(String fileName) {
        return Text.literal("Invalid scene file name '" + fileName + "'. Use only A-Z, 0-9, dot, underscore or dash.");
    }

    private Path scenePath(String fileName) {
        return scenesDir.resolve(fileName + FILE_EXTENSION);
    }

    private String normalize(String fileName) {
        return fileName.toLowerCase(Locale.ROOT);
    }
}
