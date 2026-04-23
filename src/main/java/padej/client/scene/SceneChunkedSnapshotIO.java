package padej.client.scene;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.FileVisitResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SceneChunkedSnapshotIO {
    public static final int FORMAT_VERSION = 5;
    public static final String MANIFEST_FILE_NAME = "manifest.nbt";

    private static final String STORAGE_KIND = "chunked_binary_v1";
    private static final int CHUNK_MAGIC = 0x50504348; // "PPCH"
    private static final int CHUNK_FORMAT_VERSION = 1;
    private static final int BLOCK_FLAG_HAS_BLOCK_ENTITY = 1;
    private static final int MAX_BLOCK_ENTITY_PAYLOAD_BYTES = 1_048_576;

    private SceneChunkedSnapshotIO() {
    }

    public record SceneWriteData(
            String sceneName,
            String dimensionId,
            int centerBlockX,
            int centerBlockY,
            int centerBlockZ,
            float captureYaw,
            Vec3d skyColor,
            List<BlockState> palette,
            List<ChunkData> chunks
    ) {
        public SceneWriteData {
            palette = List.copyOf(palette);
            chunks = List.copyOf(chunks);
        }
    }

    public record ChunkData(
            int chunkX,
            int chunkZ,
            List<ChunkBlock> blocks,
            List<ChunkLightSample> lightSamples
    ) {
        public ChunkData {
            blocks = List.copyOf(blocks);
            lightSamples = List.copyOf(lightSamples);
        }
    }

    public record ChunkBlock(
            int localX,
            int relY,
            int localZ,
            int paletteIndex,
            int encodedLight,
            @Nullable NbtCompound blockEntityNbt
    ) {
        public ChunkBlock {
            blockEntityNbt = blockEntityNbt == null ? null : blockEntityNbt.copy();
        }
    }

    public record ChunkLightSample(
            int localX,
            int relY,
            int localZ,
            int encodedLight
    ) {
    }

    public static void write(Path sceneDir, SceneWriteData data) throws IOException {
        write(sceneDir, data, null);
    }

    public static void write(Path sceneDir, SceneWriteData data, @Nullable WriteProgressListener progressListener) throws IOException {
        Path tempDir = sceneDir.resolveSibling(sceneDir.getFileName() + ".tmp");
        deleteRecursivelyIfExists(tempDir);
        Files.createDirectories(tempDir);

        List<ChunkData> sortedChunks = new ArrayList<>(data.chunks());
        sortedChunks.sort(Comparator
                .comparingInt(ChunkData::chunkX)
                .thenComparingInt(ChunkData::chunkZ));

        int totalFiles = sortedChunks.size() + 1; // all chunk files + manifest
        int writtenFiles = 0;

        for (ChunkData chunk : sortedChunks) {
            writeChunkFile(tempDir.resolve(chunkFileName(chunk.chunkX(), chunk.chunkZ())), chunk);
            writtenFiles++;
            if (progressListener != null) {
                progressListener.onProgress(writtenFiles, totalFiles);
            }
        }
        writeManifest(tempDir.resolve(MANIFEST_FILE_NAME), data, sortedChunks);
        writtenFiles++;
        if (progressListener != null) {
            progressListener.onProgress(writtenFiles, totalFiles);
        }

        deleteRecursivelyIfExists(sceneDir);
        try {
            Files.move(tempDir, sceneDir, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempDir, sceneDir, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    public interface WriteProgressListener {
        void onProgress(int writtenFiles, int totalFiles);
    }

    public static SceneSnapshot read(Path sceneDir, String expectedSceneName) throws IOException {
        Path manifestPath = sceneDir.resolve(MANIFEST_FILE_NAME);
        NbtCompound root = NbtIo.readCompressed(manifestPath, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) {
            throw new IOException("Scene manifest is empty: " + manifestPath);
        }

        int version = root.getInt("format");
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported chunked scene format version: " + version);
        }

        if (!root.contains("storage", NbtElement.STRING_TYPE)
                || !STORAGE_KIND.equals(root.getString("storage"))) {
            throw new IOException("Unsupported scene storage kind in " + manifestPath);
        }

        String sceneName = root.contains("scene_name", NbtElement.STRING_TYPE)
                ? root.getString("scene_name")
                : expectedSceneName;
        String dimension = root.getString("dimension");
        int centerX = root.getInt("center_x");
        int centerY = root.getInt("center_y");
        int centerZ = root.getInt("center_z");
        float captureYaw = root.getFloat("capture_yaw");
        Vec3d skyColor = root.contains("sky_r", NbtElement.DOUBLE_TYPE)
                && root.contains("sky_g", NbtElement.DOUBLE_TYPE)
                && root.contains("sky_b", NbtElement.DOUBLE_TYPE)
                ? new Vec3d(root.getDouble("sky_r"), root.getDouble("sky_g"), root.getDouble("sky_b"))
                : defaultSkyColorForDimension(dimension);

        NbtList paletteNbt = root.getList("palette", NbtElement.COMPOUND_TYPE);
        List<BlockState> palette = new ArrayList<>(paletteNbt.size());
        for (int i = 0; i < paletteNbt.size(); i++) {
            palette.add(readState(paletteNbt.getCompound(i)));
        }

        NbtList chunksNbt = root.getList("chunks", NbtElement.COMPOUND_TYPE);
        List<SceneSnapshot.SceneBlock> blocks = new ArrayList<>();
        List<SceneSnapshot.LightSample> lightSamples = new ArrayList<>();
        for (int i = 0; i < chunksNbt.size(); i++) {
            NbtCompound chunkTag = chunksNbt.getCompound(i);
            int chunkX = chunkTag.getInt("x");
            int chunkZ = chunkTag.getInt("z");
            Path chunkPath = sceneDir.resolve(chunkFileName(chunkX, chunkZ));
            if (!Files.exists(chunkPath)) {
                continue;
            }
            readChunkFile(chunkPath, chunkX, chunkZ, centerX, centerZ, palette, blocks, lightSamples);
        }

        return new SceneSnapshot(sceneName, dimension, centerX, centerY, centerZ, captureYaw, skyColor, blocks, lightSamples);
    }

    private static void writeManifest(Path path, SceneWriteData data, List<ChunkData> sortedChunks) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("format", FORMAT_VERSION);
        root.putString("storage", STORAGE_KIND);
        root.putString("scene_name", data.sceneName());
        root.putString("dimension", data.dimensionId());
        root.putInt("center_x", data.centerBlockX());
        root.putInt("center_y", data.centerBlockY());
        root.putInt("center_z", data.centerBlockZ());
        root.putFloat("capture_yaw", data.captureYaw());
        root.putDouble("sky_r", data.skyColor().x);
        root.putDouble("sky_g", data.skyColor().y);
        root.putDouble("sky_b", data.skyColor().z);

        NbtList palette = new NbtList();
        for (BlockState state : data.palette()) {
            palette.add(writeState(state));
        }
        root.put("palette", palette);

        NbtList chunks = new NbtList();
        for (ChunkData chunk : sortedChunks) {
            NbtCompound chunkTag = new NbtCompound();
            chunkTag.putInt("x", chunk.chunkX());
            chunkTag.putInt("z", chunk.chunkZ());
            chunkTag.putInt("b", chunk.blocks().size());
            chunkTag.putInt("l", chunk.lightSamples().size());
            chunks.add(chunkTag);
        }
        root.put("chunks", chunks);

        NbtIo.writeCompressed(root, path);
    }

    private static void writeChunkFile(Path path, ChunkData chunk) throws IOException {
        try (OutputStream rawOut = Files.newOutputStream(path);
             GZIPOutputStream gzipOut = new GZIPOutputStream(rawOut);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(gzipOut))) {
            output.writeInt(CHUNK_MAGIC);
            output.writeInt(CHUNK_FORMAT_VERSION);
            output.writeInt(chunk.blocks().size());
            output.writeInt(chunk.lightSamples().size());

            for (ChunkBlock block : chunk.blocks()) {
                output.writeShort(block.paletteIndex());
                output.writeShort(block.encodedLight());
                output.writeByte(block.localX());
                output.writeByte(block.localZ());
                output.writeShort(block.relY());
                int flags = block.blockEntityNbt() != null ? BLOCK_FLAG_HAS_BLOCK_ENTITY : 0;
                output.writeByte(flags);
                if (block.blockEntityNbt() != null) {
                    writeBlockEntityPayload(output, block.blockEntityNbt());
                }
            }

            for (ChunkLightSample sample : chunk.lightSamples()) {
                output.writeShort(sample.encodedLight());
                output.writeByte(sample.localX());
                output.writeByte(sample.localZ());
                output.writeShort(sample.relY());
            }
        }
    }

    private static void readChunkFile(
            Path path,
            int chunkX,
            int chunkZ,
            int centerX,
            int centerZ,
            List<BlockState> palette,
            List<SceneSnapshot.SceneBlock> blocks,
            List<SceneSnapshot.LightSample> lightSamples
    ) throws IOException {
        try (InputStream rawIn = Files.newInputStream(path);
             GZIPInputStream gzipIn = new GZIPInputStream(rawIn);
             DataInputStream input = new DataInputStream(new BufferedInputStream(gzipIn))) {
            int magic = input.readInt();
            if (magic != CHUNK_MAGIC) {
                throw new IOException("Invalid chunk magic in " + path);
            }
            int chunkVersion = input.readInt();
            if (chunkVersion != CHUNK_FORMAT_VERSION) {
                throw new IOException("Unsupported chunk file version in " + path + ": " + chunkVersion);
            }

            int blockCount = input.readInt();
            int lightCount = input.readInt();
            if (blockCount < 0 || lightCount < 0) {
                throw new IOException("Corrupted chunk counters in " + path);
            }

            for (int i = 0; i < blockCount; i++) {
                int paletteIndex = Short.toUnsignedInt(input.readShort());
                int encodedLight = Short.toUnsignedInt(input.readShort());
                int localX = Byte.toUnsignedInt(input.readByte());
                int localZ = Byte.toUnsignedInt(input.readByte());
                int relY = input.readShort();
                int flags = Byte.toUnsignedInt(input.readByte());
                NbtCompound blockEntityNbt = (flags & BLOCK_FLAG_HAS_BLOCK_ENTITY) != 0
                        ? readBlockEntityPayload(input)
                        : null;

                if (paletteIndex < 0 || paletteIndex >= palette.size()) {
                    continue;
                }

                int relX = (chunkX << 4) + localX - centerX;
                int relZ = (chunkZ << 4) + localZ - centerZ;
                blocks.add(new SceneSnapshot.SceneBlock(
                        relX,
                        relY,
                        relZ,
                        palette.get(paletteIndex),
                        decodePackedLight(encodedLight),
                        blockEntityNbt
                ));
            }

            for (int i = 0; i < lightCount; i++) {
                int encodedLight = Short.toUnsignedInt(input.readShort());
                int localX = Byte.toUnsignedInt(input.readByte());
                int localZ = Byte.toUnsignedInt(input.readByte());
                int relY = input.readShort();
                int relX = (chunkX << 4) + localX - centerX;
                int relZ = (chunkZ << 4) + localZ - centerZ;
                lightSamples.add(new SceneSnapshot.LightSample(
                        relX,
                        relY,
                        relZ,
                        decodePackedLight(encodedLight)
                ));
            }
        }
    }

    private static void writeBlockEntityPayload(DataOutputStream output, NbtCompound blockEntityNbt) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeCompressed(blockEntityNbt, buffer);
        byte[] payload = buffer.toByteArray();
        output.writeInt(payload.length);
        output.write(payload);
    }

    private static NbtCompound readBlockEntityPayload(DataInputStream input) throws IOException {
        int payloadLength = input.readInt();
        if (payloadLength < 0 || payloadLength > MAX_BLOCK_ENTITY_PAYLOAD_BYTES) {
            throw new IOException("Invalid block entity payload length: " + payloadLength);
        }
        byte[] payload = input.readNBytes(payloadLength);
        if (payload.length != payloadLength) {
            throw new EOFException("Unexpected EOF while reading block entity payload");
        }
        NbtCompound read = NbtIo.readCompressed(new ByteArrayInputStream(payload), NbtSizeTracker.ofUnlimitedBytes());
        return read == null ? new NbtCompound() : read;
    }

    public static String chunkFileName(int chunkX, int chunkZ) {
        return "c." + chunkX + "." + chunkZ + ".bin";
    }

    private static void deleteRecursivelyIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) {
                    throw exc;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static NbtCompound writeState(BlockState state) {
        NbtCompound out = new NbtCompound();
        out.putString("id", Registries.BLOCK.getId(state.getBlock()).toString());

        if (!state.getEntries().isEmpty()) {
            NbtCompound propertiesNbt = new NbtCompound();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getEntries().entrySet()) {
                propertiesNbt.putString(entry.getKey().getName(), propertyValueToString(entry.getKey(), entry.getValue()));
            }
            out.put("props", propertiesNbt);
        }

        return out;
    }

    private static BlockState readState(NbtCompound in) {
        Identifier blockId = Identifier.tryParse(in.getString("id"));
        if (blockId == null || !Registries.BLOCK.containsId(blockId)) {
            return Registries.BLOCK.get(Identifier.of("minecraft", "air")).getDefaultState();
        }

        Block block = Registries.BLOCK.get(blockId);
        BlockState state = block.getDefaultState();
        if (!in.contains("props", NbtElement.COMPOUND_TYPE)) {
            return state;
        }

        NbtCompound propsNbt = in.getCompound("props");
        for (Property<?> property : state.getProperties()) {
            if (!propsNbt.contains(property.getName(), NbtElement.STRING_TYPE)) {
                continue;
            }
            state = applyProperty(state, property, propsNbt.getString(property.getName()));
        }
        return state;
    }

    private static int decodePackedLight(int encodedLight) {
        int block = encodedLight & 0xFF;
        int sky = (encodedLight >>> 8) & 0xFF;
        return LightmapTextureManager.pack(block, sky);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, Property<?> property, String rawValue) {
        Optional optional = property.parse(rawValue.toLowerCase(Locale.ROOT));
        if (optional.isEmpty()) {
            return state;
        }
        return state.with((Property) property, (Comparable) optional.get());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValueToString(Property<?> property, Comparable<?> value) {
        return ((Property<T>) property).name((T) value);
    }

    private static Vec3d defaultSkyColorForDimension(String dimensionId) {
        if ("minecraft:the_nether".equals(dimensionId)) {
            return new Vec3d(0.2D, 0.03D, 0.03D);
        }
        if ("minecraft:the_end".equals(dimensionId)) {
            return new Vec3d(0.04D, 0.04D, 0.1D);
        }
        return new Vec3d(0.62D, 0.73D, 1.0D);
    }
}
