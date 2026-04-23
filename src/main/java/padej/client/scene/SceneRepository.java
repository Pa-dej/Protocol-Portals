package padej.client.scene;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class SceneRepository {
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
    private static final String LEGACY_FILE_EXTENSION = ".nbt";
    private static final int MIN_CAPTURE_CHUNK_RADIUS = 2;
    private static final int MAX_CAPTURE_CHUNK_RADIUS = 16;
    private static final int CAPTURE_BLOCKS_BELOW_PLAYER = 20;
    private static final boolean CAPTURE_SURFACE_ONLY = true;
    private static final int CAPTURED_LOADED_CHUNKS_PER_TICK = 2;
    private static final int UNLOADED_CHUNK_PROBES_PER_TICK = 24;
    private static final int MAX_CAPTURE_WAIT_TICKS = 20 * 90;
    private static final Direction[] NEIGHBOR_DIRECTIONS = Direction.values();

    private final Path scenesDir;
    private final Map<String, SceneSnapshot> cache = new HashMap<>();
    private final ExecutorService ioExecutor;
    private volatile int manualCaptureChunkRadius = -1;
    @Nullable
    private CaptureSession activeCapture;
    @Nullable
    private volatile CaptureHudState hudState;

    public record CaptureResult(
            String sceneName,
            int blockCount,
            int lightSampleCount,
            int chunkCount,
            int requestedChunkCount,
            int skippedUnloadedChunks,
            boolean truncated,
            int horizontalRadius,
            int verticalRadius,
            Path scenePath
    ) {
    }

    public record CaptureStartResult(
            String sceneName,
            int totalChunks,
            int horizontalRadius,
            int verticalRadius
    ) {
    }

    public enum CaptureStage {
        CAPTURING,
        SAVING
    }

    public record CaptureHudState(
            String sceneName,
            CaptureStage stage,
            int done,
            int total,
            String detail
    ) {
        public float progress() {
            if (total <= 0) {
                return 1.0F;
            }
            return Math.min(1.0F, Math.max(0.0F, (float) done / (float) total));
        }
    }

    public SceneRepository() {
        this.scenesDir = FabricLoader.getInstance().getConfigDir().resolve("protocol-portals").resolve("scenes");
        this.ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "protocol-portals-scene-io");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path scenesDir() {
        return scenesDir;
    }

    public int minCaptureChunkRadius() {
        return MIN_CAPTURE_CHUNK_RADIUS;
    }

    public int maxCaptureChunkRadius() {
        return MAX_CAPTURE_CHUNK_RADIUS;
    }

    public int manualCaptureChunkRadius() {
        return manualCaptureChunkRadius;
    }

    public boolean isManualCaptureChunkRadiusEnabled() {
        return manualCaptureChunkRadius >= MIN_CAPTURE_CHUNK_RADIUS;
    }

    public int effectiveCaptureChunkRadius(MinecraftClient client) {
        return resolveCaptureChunkRadius(client);
    }

    public void setCaptureChunkRadiusAuto() {
        manualCaptureChunkRadius = -1;
    }

    public void setCaptureChunkRadiusManual(int radius) {
        manualCaptureChunkRadius = clampCaptureChunkRadius(radius);
    }

    @Nullable
    public CaptureHudState hudState() {
        return hudState;
    }

    public boolean isValidFileName(String fileName) {
        return FILE_NAME_PATTERN.matcher(fileName).matches();
    }

    public Optional<SceneSnapshot> load(String fileName) {
        String normalized = normalize(fileName);
        if (cache.containsKey(normalized)) {
            return Optional.of(cache.get(normalized));
        }

        Path chunkedSceneDir = sceneDirectoryPath(normalized);
        Path legacyPath = legacyScenePath(normalized);
        if (!Files.exists(chunkedSceneDir) && !Files.exists(legacyPath)) {
            return Optional.empty();
        }

        try {
            SceneSnapshot snapshot;
            if (Files.isDirectory(chunkedSceneDir)
                    && Files.exists(chunkedSceneDir.resolve(SceneChunkedSnapshotIO.MANIFEST_FILE_NAME))) {
                snapshot = SceneChunkedSnapshotIO.read(chunkedSceneDir, normalized);
            } else {
                snapshot = SceneSnapshotIO.read(legacyPath, normalized);
            }
            cache.put(normalized, snapshot);
            return Optional.of(snapshot);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    public CaptureStartResult startCapture(
            MinecraftClient client,
            String fileName,
            Consumer<CaptureResult> onSuccess,
            Consumer<String> onFailure
    ) throws IOException {
        if (activeCapture != null) {
            throw new IOException("Snapshot '" + activeCapture.sceneName + "' is already being captured.");
        }

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
        int captureChunkRadius = resolveCaptureChunkRadius(client);
        int centerChunkX = Math.floorDiv(centerX, 16);
        int centerChunkZ = Math.floorDiv(centerZ, 16);
        int minChunkX = centerChunkX - captureChunkRadius;
        int maxChunkX = centerChunkX + captureChunkRadius;
        int minChunkZ = centerChunkZ - captureChunkRadius;
        int maxChunkZ = centerChunkZ + captureChunkRadius;

        int minX = minChunkX * 16;
        int maxX = maxChunkX * 16 + 15;
        int minZ = minChunkZ * 16;
        int maxZ = maxChunkZ * 16 + 15;

        int solidBlockMinY = Math.max(world.getBottomY(), centerY - CAPTURE_BLOCKS_BELOW_PLAYER);
        int minY = world.getBottomY();
        int maxY = world.getTopY() - 1;
        int horizontalRadiusBlocks = captureChunkRadius * 16;
        int verticalRadiusBlocks = Math.max(centerY - solidBlockMinY, maxY - centerY);

        Queue<ChunkPos> pendingChunks = new ArrayDeque<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                pendingChunks.add(new ChunkPos(chunkX, chunkZ));
            }
        }

        activeCapture = new CaptureSession(
                normalized,
                centerX,
                centerY,
                centerZ,
                solidBlockMinY,
                minX,
                maxX,
                minY,
                maxY,
                minZ,
                maxZ,
                world,
                world.getRegistryKey().getValue().toString(),
                player.getYaw(),
                captureSkyColor(world, player.getEyePos()),
                pendingChunks,
                horizontalRadiusBlocks,
                verticalRadiusBlocks,
                pendingChunks.size(),
                onSuccess,
                onFailure
        );
        cache.remove(normalized);
        updateCaptureHud(activeCapture);

        return new CaptureStartResult(
                normalized,
                pendingChunks.size(),
                horizontalRadiusBlocks,
                verticalRadiusBlocks
        );
    }

    public void tick(MinecraftClient client) {
        CaptureSession session = activeCapture;
        if (session == null) {
            return;
        }

        session.elapsedTicks++;

        if (client.world == null || client.player == null || client.world != session.world) {
            activeCapture = null;
            clearHud();
            session.onFailure.accept("Snapshot capture cancelled: world changed before completion.");
            return;
        }

        try {
            int capturedLoadedThisTick = 0;
            int unloadedProbesThisTick = 0;
            int scanBudget = CAPTURED_LOADED_CHUNKS_PER_TICK + UNLOADED_CHUNK_PROBES_PER_TICK;
            while (!session.pendingChunks.isEmpty() && scanBudget > 0) {
                ChunkPos chunkPos = session.pendingChunks.poll();
                if (chunkPos == null) {
                    continue;
                }
                scanBudget--;

                boolean loaded = session.world.isChunkLoaded(chunkPos.x, chunkPos.z);
                if (!loaded) {
                    if (unloadedProbesThisTick >= UNLOADED_CHUNK_PROBES_PER_TICK) {
                        session.pendingChunks.add(chunkPos);
                        continue;
                    }
                    unloadedProbesThisTick++;
                    if (session.elapsedTicks < MAX_CAPTURE_WAIT_TICKS) {
                        // Keep unloaded chunks in the queue so chunks that are currently loading get captured.
                        session.pendingChunks.add(chunkPos);
                    } else {
                        session.completedChunks++;
                        session.skippedUnloadedChunks++;
                    }
                    continue;
                }

                if (capturedLoadedThisTick >= CAPTURED_LOADED_CHUNKS_PER_TICK) {
                    // Loaded capture budget reached for this tick; try this chunk on next tick.
                    session.pendingChunks.add(chunkPos);
                    continue;
                }

                boolean captured = captureChunk(session, chunkPos);
                if (captured) {
                    session.completedChunks++;
                    capturedLoadedThisTick++;
                } else {
                    // Chunk can unload between probe and capture. Treat as unloaded retry.
                    if (session.elapsedTicks < MAX_CAPTURE_WAIT_TICKS) {
                        session.pendingChunks.add(chunkPos);
                    } else {
                        session.completedChunks++;
                        session.skippedUnloadedChunks++;
                    }
                }
            }
        } catch (IOException exception) {
            activeCapture = null;
            clearHud();
            session.onFailure.accept("Snapshot capture failed: " + exception.getMessage());
            return;
        }

        if (session.elapsedTicks >= MAX_CAPTURE_WAIT_TICKS && !session.pendingChunks.isEmpty()) {
            session.skippedUnloadedChunks += session.pendingChunks.size();
            session.completedChunks += session.pendingChunks.size();
            session.pendingChunks.clear();
        }

        updateCaptureHud(session);

        if (session.completedChunks >= session.totalChunks || session.pendingChunks.isEmpty()) {
            activeCapture = null;
            persistCapturedSceneAsync(client, session);
        }
    }

    public static Text invalidNameText(String fileName) {
        return Text.literal("Invalid scene file name '" + fileName + "'. Use only A-Z, 0-9, dot, underscore or dash.");
    }

    private boolean captureChunk(CaptureSession session, ChunkPos chunkPos) throws IOException {
        if (!session.world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return false;
        }

        WorldChunk chunk = session.world.getChunk(chunkPos.x, chunkPos.z);
        if (chunk.isEmpty()) {
            return true;
        }

        int chunkStartX = chunkPos.x * 16;
        int chunkStartZ = chunkPos.z * 16;
        int startX = Math.max(session.minX, chunkStartX);
        int endX = Math.min(session.maxX, chunkStartX + 15);
        int startZ = Math.max(session.minZ, chunkStartZ);
        int endZ = Math.min(session.maxZ, chunkStartZ + 15);

        List<SceneChunkedSnapshotIO.ChunkBlock> blocks = new ArrayList<>();
        List<SceneChunkedSnapshotIO.ChunkLightSample> lightSamples = new ArrayList<>();

        for (int y = session.minY; y <= session.maxY; y++) {
            int relY = y - session.centerY;
            for (int worldX = startX; worldX <= endX; worldX++) {
                int localX = worldX - chunkStartX;
                for (int worldZ = startZ; worldZ <= endZ; worldZ++) {
                    int localZ = worldZ - chunkStartZ;

                    session.mutableChunkPos.set(localX, y, localZ);
                    BlockState state = chunk.getBlockState(session.mutableChunkPos);
                    session.mutableWorldPos.set(worldX, y, worldZ);

                    int blockLight = session.world.getLightLevel(LightType.BLOCK, session.mutableWorldPos);
                    int skyLight = session.world.getLightLevel(LightType.SKY, session.mutableWorldPos);
                    int encodedLight = encodeLight(blockLight, skyLight);

                    if (state.isAir()) {
                        // Preserve propagated block light around emitters (torches, lanterns, etc.).
                        if (blockLight > 0) {
                            lightSamples.add(new SceneChunkedSnapshotIO.ChunkLightSample(localX, relY, localZ, encodedLight));
                            session.totalLightSamples++;
                        }
                        continue;
                    }

                    boolean denseFullCube = state.isOpaqueFullCube(session.world, session.mutableWorldPos);
                    if (denseFullCube && y < session.solidBlockMinY) {
                        continue;
                    }

                    if (CAPTURE_SURFACE_ONLY && denseFullCube && !shouldCaptureSolidBlock(
                            session.world,
                            session.mutableWorldPos,
                            session.mutableNeighborPos,
                            state,
                            session.minX,
                            session.maxX,
                            session.minY,
                            session.maxY,
                            session.minZ,
                            session.maxZ
                    )) {
                        continue;
                    }

                    BlockEntity blockEntity = session.world.getBlockEntity(session.mutableWorldPos);
                    NbtCompound blockEntityNbt = blockEntity != null
                            ? blockEntity.createNbt(session.world.getRegistryManager())
                            : null;

                    blocks.add(new SceneChunkedSnapshotIO.ChunkBlock(
                            localX,
                            relY,
                            localZ,
                            resolvePaletteIndex(session, state),
                            encodedLight,
                            blockEntityNbt
                    ));
                    session.totalBlocks++;
                }
            }
        }

        if (!blocks.isEmpty() || !lightSamples.isEmpty()) {
            session.capturedChunks.add(new SceneChunkedSnapshotIO.ChunkData(
                    chunkPos.x,
                    chunkPos.z,
                    blocks,
                    lightSamples
            ));
        }
        return true;
    }

    private void persistCapturedSceneAsync(MinecraftClient client, CaptureSession session) {
        Path scenePath = sceneDirectoryPath(session.sceneName);
        Path legacyPath = legacyScenePath(session.sceneName);
        SceneChunkedSnapshotIO.SceneWriteData writeData = new SceneChunkedSnapshotIO.SceneWriteData(
                session.sceneName,
                session.dimensionId,
                session.centerX,
                session.centerY,
                session.centerZ,
                session.captureYaw,
                session.skyColor,
                session.palette,
                session.capturedChunks
        );
        int totalFiles = Math.max(1, session.capturedChunks.size() + 1);
        updateSavingHud(session, 0, totalFiles);

        CompletableFuture.runAsync(() -> {
            try {
                SceneChunkedSnapshotIO.write(scenePath, writeData, (writtenFiles, total) ->
                        updateSavingHud(session, writtenFiles, total));
                Files.deleteIfExists(legacyPath);

                CaptureResult result = new CaptureResult(
                        session.sceneName,
                        session.totalBlocks,
                        session.totalLightSamples,
                        session.capturedChunks.size(),
                        session.totalChunks,
                        session.skippedUnloadedChunks,
                        false,
                        session.horizontalRadius,
                        session.verticalRadius,
                        scenePath
                );
                client.execute(() -> {
                    clearHud();
                    cache.remove(session.sceneName);
                    session.onSuccess.accept(result);
                });
            } catch (IOException exception) {
                String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
                client.execute(() -> {
                    clearHud();
                    session.onFailure.accept("Snapshot save failed: " + message);
                });
            }
        }, ioExecutor);
    }

    private static int resolvePaletteIndex(CaptureSession session, BlockState state) throws IOException {
        Integer existing = session.paletteIndex.get(state);
        if (existing != null) {
            return existing;
        }

        int nextIndex = session.palette.size();
        if (nextIndex > 0xFFFF) {
            throw new IOException("Scene palette is too large (>65535 states).");
        }

        session.palette.add(state);
        session.paletteIndex.put(state, nextIndex);
        return nextIndex;
    }

    private static int encodeLight(int blockLight, int skyLight) {
        return (blockLight & 0xFF) | ((skyLight & 0xFF) << 8);
    }

    private int resolveCaptureChunkRadius(MinecraftClient client) {
        int manual = manualCaptureChunkRadius;
        if (manual >= MIN_CAPTURE_CHUNK_RADIUS) {
            return clampCaptureChunkRadius(manual);
        }

        int configuredRadius = MAX_CAPTURE_CHUNK_RADIUS;
        if (client.options != null && client.options.getViewDistance() != null) {
            configuredRadius = client.options.getViewDistance().getValue();
        }
        return clampCaptureChunkRadius(configuredRadius);
    }

    private static int clampCaptureChunkRadius(int radius) {
        return Math.max(MIN_CAPTURE_CHUNK_RADIUS, Math.min(MAX_CAPTURE_CHUNK_RADIUS, radius));
    }

    private void updateCaptureHud(CaptureSession session) {
        String detail = "chunks " + session.completedChunks + "/" + session.totalChunks
                + ", waiting " + session.pendingChunks.size()
                + ", skipped unloaded " + session.skippedUnloadedChunks;
        hudState = new CaptureHudState(
                session.sceneName,
                CaptureStage.CAPTURING,
                session.completedChunks,
                session.totalChunks,
                detail
        );
    }

    private void updateSavingHud(CaptureSession session, int writtenFiles, int totalFiles) {
        String detail = "files " + writtenFiles + "/" + totalFiles
                + ", blocks " + session.totalBlocks
                + ", lights " + session.totalLightSamples;
        hudState = new CaptureHudState(
                session.sceneName,
                CaptureStage.SAVING,
                writtenFiles,
                totalFiles,
                detail
        );
    }

    private void clearHud() {
        hudState = null;
    }

    private Path sceneDirectoryPath(String fileName) {
        return scenesDir.resolve(fileName);
    }

    private Path legacyScenePath(String fileName) {
        return scenesDir.resolve(fileName + LEGACY_FILE_EXTENSION);
    }

    private String normalize(String fileName) {
        return fileName.toLowerCase(Locale.ROOT);
    }

    private static boolean shouldCaptureSolidBlock(
            ClientWorld world,
            BlockPos.Mutable worldPos,
            BlockPos.Mutable neighborPos,
            BlockState state,
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
    ) {
        // Keep all non-full or non-opaque blocks (stairs, slabs, fences, glass, foliage, etc.).
        if (!state.isOpaqueFullCube(world, worldPos)) {
            return true;
        }

        int x = worldPos.getX();
        int y = worldPos.getY();
        int z = worldPos.getZ();

        // Keep dense full-cube blocks only if they touch air (surface shell).
        for (Direction direction : NEIGHBOR_DIRECTIONS) {
            int nx = x + direction.getOffsetX();
            int ny = y + direction.getOffsetY();
            int nz = z + direction.getOffsetZ();

            if (nx < minX || nx > maxX || ny < minY || ny > maxY || nz < minZ || nz > maxZ) {
                return true;
            }

            neighborPos.set(nx, ny, nz);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (neighborState.isAir() || !neighborState.isOpaqueFullCube(world, neighborPos)) {
                return true;
            }
        }

        return false;
    }

    private static Vec3d captureSkyColor(ClientWorld world, Vec3d cameraPos) {
        return world.getSkyColor(cameraPos, 1.0F);
    }

    private static final class CaptureSession {
        private final String sceneName;
        private final int centerX;
        private final int centerY;
        private final int centerZ;
        private final int solidBlockMinY;
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;
        private final int minZ;
        private final int maxZ;
        private final ClientWorld world;
        private final String dimensionId;
        private final float captureYaw;
        private final Vec3d skyColor;
        private final Queue<ChunkPos> pendingChunks;
        private final int horizontalRadius;
        private final int verticalRadius;
        private final int totalChunks;
        private final Consumer<CaptureResult> onSuccess;
        private final Consumer<String> onFailure;

        private final List<SceneChunkedSnapshotIO.ChunkData> capturedChunks = new ArrayList<>();
        private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
        private final List<BlockState> palette = new ArrayList<>();
        private final BlockPos.Mutable mutableChunkPos = new BlockPos.Mutable();
        private final BlockPos.Mutable mutableWorldPos = new BlockPos.Mutable();
        private final BlockPos.Mutable mutableNeighborPos = new BlockPos.Mutable();

        private int totalBlocks;
        private int totalLightSamples;
        private int completedChunks;
        private int skippedUnloadedChunks;
        private int elapsedTicks;

        private CaptureSession(
                String sceneName,
                int centerX,
                int centerY,
                int centerZ,
                int solidBlockMinY,
                int minX,
                int maxX,
                int minY,
                int maxY,
                int minZ,
                int maxZ,
                ClientWorld world,
                String dimensionId,
                float captureYaw,
                Vec3d skyColor,
                Queue<ChunkPos> pendingChunks,
                int horizontalRadius,
                int verticalRadius,
                int totalChunks,
                Consumer<CaptureResult> onSuccess,
                Consumer<String> onFailure
        ) {
            this.sceneName = sceneName;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.solidBlockMinY = solidBlockMinY;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.world = world;
            this.dimensionId = dimensionId;
            this.captureYaw = captureYaw;
            this.skyColor = skyColor;
            this.pendingChunks = pendingChunks;
            this.horizontalRadius = horizontalRadius;
            this.verticalRadius = verticalRadius;
            this.totalChunks = totalChunks;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }
    }
}
