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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class SceneSnapshotIO {
    private static final int FORMAT_VERSION = 2;

    private SceneSnapshotIO() {
    }

    public static void write(Path path, SceneSnapshot snapshot) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putInt("format", FORMAT_VERSION);
        root.putString("scene_name", snapshot.sceneName());
        root.putString("dimension", snapshot.dimensionId());
        root.putInt("center_x", snapshot.centerBlockX());
        root.putInt("center_y", snapshot.centerBlockY());
        root.putInt("center_z", snapshot.centerBlockZ());
        root.putFloat("capture_yaw", snapshot.captureYaw());

        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        NbtList palette = new NbtList();
        NbtList blocks = new NbtList();

        for (SceneSnapshot.SceneBlock block : snapshot.blocks()) {
            Integer index = paletteIndex.get(block.state());
            if (index == null) {
                index = palette.size();
                paletteIndex.put(block.state(), index);
                palette.add(writeState(block.state()));
            }

            NbtCompound blockNbt = new NbtCompound();
            blockNbt.putInt("x", block.relX());
            blockNbt.putInt("y", block.relY());
            blockNbt.putInt("z", block.relZ());
            blockNbt.putInt("p", index);
            blockNbt.putInt("l", block.packedLight());
            if (block.blockEntityNbt() != null) {
                blockNbt.put("be", block.blockEntityNbt().copy());
            }
            blocks.add(blockNbt);
        }

        root.put("palette", palette);
        root.put("blocks", blocks);
        NbtIo.writeCompressed(root, path);
    }

    public static SceneSnapshot read(Path path, String expectedSceneName) throws IOException {
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        if (root == null) {
            throw new IOException("Scene file is empty: " + path);
        }

        int version = root.getInt("format");
        if (version != 1 && version != FORMAT_VERSION) {
            throw new IOException("Unsupported scene format version: " + version);
        }

        String sceneName = root.contains("scene_name", NbtElement.STRING_TYPE)
                ? root.getString("scene_name")
                : expectedSceneName;
        String dimension = root.getString("dimension");
        int centerX = root.getInt("center_x");
        int centerY = root.getInt("center_y");
        int centerZ = root.getInt("center_z");
        float captureYaw = root.getFloat("capture_yaw");

        NbtList paletteNbt = root.getList("palette", NbtElement.COMPOUND_TYPE);
        List<BlockState> palette = new ArrayList<>(paletteNbt.size());
        for (int i = 0; i < paletteNbt.size(); i++) {
            palette.add(readState(paletteNbt.getCompound(i)));
        }

        NbtList blocksNbt = root.getList("blocks", NbtElement.COMPOUND_TYPE);
        List<SceneSnapshot.SceneBlock> blocks = new ArrayList<>(blocksNbt.size());
        for (int i = 0; i < blocksNbt.size(); i++) {
            NbtCompound blockNbt = blocksNbt.getCompound(i);
            int palettePointer = blockNbt.getInt("p");
            if (palettePointer < 0 || palettePointer >= palette.size()) {
                continue;
            }

            int packedLight = version >= 2 && blockNbt.contains("l", NbtElement.INT_TYPE)
                    ? blockNbt.getInt("l")
                    : LightmapTextureManager.MAX_LIGHT_COORDINATE;
            NbtCompound blockEntityNbt = version >= 2 && blockNbt.contains("be", NbtElement.COMPOUND_TYPE)
                    ? blockNbt.getCompound("be").copy()
                    : null;

            blocks.add(new SceneSnapshot.SceneBlock(
                    blockNbt.getInt("x"),
                    blockNbt.getInt("y"),
                    blockNbt.getInt("z"),
                    palette.get(palettePointer),
                    packedLight,
                    blockEntityNbt
            ));
        }

        return new SceneSnapshot(sceneName, dimension, centerX, centerY, centerZ, captureYaw, blocks);
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
}
