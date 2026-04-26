package padej.client.portal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import padej.client.scene.SceneRepository;
import padej.client.scene.SceneSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class PortalPersistenceService {
    private static final int FILE_VERSION = 1;
    private static final long SAVE_DEBOUNCE_TICKS = 10L;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final SceneRepository sceneRepository;
    private final PortalManager portalManager;
    private final Path worldsPortalsDir;
    private final Path serversPortalsDir;

    @Nullable
    private StorageContext currentContext;
    private String observedFingerprint = "";
    private String lastSavedFingerprint = "";
    private long pendingSaveSinceTick = -1L;
    private long tickCounter = 0L;

    public PortalPersistenceService(SceneRepository sceneRepository, PortalManager portalManager) {
        this.sceneRepository = sceneRepository;
        this.portalManager = portalManager;

        Path rootDir = FabricLoader.getInstance().getGameDir()
                .resolve("zone_core")
                .resolve("protocol_portals");
        this.worldsPortalsDir = rootDir.resolve("worlds").resolve("portals");
        this.serversPortalsDir = rootDir.resolve("servers").resolve("portals");
    }

    public void tick(MinecraftClient client) {
        tickCounter++;

        StorageContext nextContext = resolveContext(client);
        if (nextContext == null) {
            flushPendingSave();
            resetContextTracking();
            return;
        }

        if (!nextContext.equals(currentContext)) {
            flushPendingSave();
            currentContext = nextContext;
            portalManager.clearPortals();
            loadCurrentContext();
            observedFingerprint = computeFingerprint(portalManager.activePortals());
            lastSavedFingerprint = observedFingerprint;
            pendingSaveSinceTick = -1L;
            return;
        }

        String latestFingerprint = computeFingerprint(portalManager.activePortals());
        if (!latestFingerprint.equals(observedFingerprint)) {
            observedFingerprint = latestFingerprint;
            pendingSaveSinceTick = tickCounter;
        }

        if (pendingSaveSinceTick >= 0L
                && tickCounter - pendingSaveSinceTick >= SAVE_DEBOUNCE_TICKS
                && !observedFingerprint.equals(lastSavedFingerprint)) {
            saveCurrentContext();
            lastSavedFingerprint = observedFingerprint;
            pendingSaveSinceTick = -1L;
        }
    }

    private void flushPendingSave() {
        if (currentContext == null) {
            return;
        }
        if (observedFingerprint.equals(lastSavedFingerprint)) {
            return;
        }

        saveCurrentContext();
        lastSavedFingerprint = observedFingerprint;
        pendingSaveSinceTick = -1L;
    }

    private void resetContextTracking() {
        currentContext = null;
        observedFingerprint = "";
        lastSavedFingerprint = "";
        pendingSaveSinceTick = -1L;
    }

    @Nullable
    private StorageContext resolveContext(MinecraftClient client) {
        if (client.world == null) {
            return null;
        }

        String dimensionName = sanitizeFileName(client.world.getRegistryKey().getValue().toString());
        if (dimensionName.isEmpty()) {
            dimensionName = "unknown_dimension";
        }

        if (client.isInSingleplayer()) {
            String worldName = "singleplayer";
            if (client.getServer() != null && client.getServer().getSaveProperties() != null) {
                String levelName = client.getServer().getSaveProperties().getLevelName();
                if (levelName != null && !levelName.isBlank()) {
                    worldName = levelName.trim();
                }
            }
            return new StorageContext(
                    "world:" + worldName,
                    worldsPortalsDir.resolve(dimensionName + ".json")
            );
        }

        ServerInfo currentServer = client.getCurrentServerEntry();
        if (currentServer == null || currentServer.address == null || currentServer.address.isBlank()) {
            return null;
        }

        String normalizedServerAddress = normalizeServerAddress(currentServer.address);
        return new StorageContext(
                "server:" + normalizedServerAddress,
                serversPortalsDir.resolve(dimensionName + ".json")
        );
    }

    private void loadCurrentContext() {
        StorageContext context = currentContext;
        if (context == null) {
            return;
        }

        PortalFileDocument document = readDocument(context.filePath());
        if (document == null || document.portals == null || document.portals.isEmpty()) {
            return;
        }

        for (StoredPortal entry : document.portals) {
            if (entry == null || entry.contextKey == null || entry.sceneName == null) {
                continue;
            }
            if (!context.contextKey().equals(entry.contextKey)) {
                continue;
            }

            String sceneName = entry.sceneName.trim().toLowerCase(Locale.ROOT);
            if (!sceneRepository.isValidFileName(sceneName)) {
                continue;
            }

            Optional<SceneSnapshot> snapshot = sceneRepository.load(sceneName);
            if (snapshot.isEmpty()) {
                System.err.println("[Protocol Portals] Saved portal scene '" + sceneName + "' is missing; skipping restore.");
                continue;
            }

            Vec3d center = fromVec3(entry.center, new Vec3d(0.0D, 64.0D, 0.0D));
            Vec3d normal = fromVec3(entry.normal, new Vec3d(0.0D, 0.0D, 1.0D));
            Vec3d right = fromVec3(entry.right, new Vec3d(1.0D, 0.0D, 0.0D));
            Vec3d up = fromVec3(entry.up, new Vec3d(0.0D, 1.0D, 0.0D));
            Vec3d sceneAnchor = fromVec3(entry.sceneAnchor, center);
            Vec3d skyColor = fromVec3(entry.skyColor, snapshot.get().skyColor());
            double width = entry.width > 0.0D ? entry.width : 3.0D;
            double height = entry.height > 0.0D ? entry.height : 3.0D;

            portalManager.restorePortalFromSnapshot(
                    sceneName,
                    entry.serverAddress,
                    snapshot.get(),
                    center,
                    normal,
                    right,
                    up,
                    sceneAnchor,
                    skyColor,
                    width,
                    height
            );
        }
    }

    private void saveCurrentContext() {
        StorageContext context = currentContext;
        if (context == null) {
            return;
        }

        PortalFileDocument document = readDocument(context.filePath());
        if (document == null) {
            document = new PortalFileDocument();
            document.version = FILE_VERSION;
            document.portals = new ArrayList<>();
        }
        if (document.portals == null) {
            document.portals = new ArrayList<>();
        }

        document.portals.removeIf(portal -> portal == null || context.contextKey().equals(portal.contextKey));

        List<PortalInstance> activePortals = portalManager.activePortals();
        for (PortalInstance portal : activePortals) {
            document.portals.add(toStoredPortal(context.contextKey(), portal));
        }

        try {
            if (document.portals.isEmpty()) {
                Files.deleteIfExists(context.filePath());
                return;
            }

            Files.createDirectories(context.filePath().getParent());
            String json = GSON.toJson(document);
            Path tempPath = context.filePath().resolveSibling(context.filePath().getFileName().toString() + ".tmp");
            Files.writeString(
                    tempPath,
                    json,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(tempPath, context.filePath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(tempPath, context.filePath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ioException) {
            System.err.println("[Protocol Portals] Failed to save portal placements: " + ioException.getMessage());
        }
    }

    @Nullable
    private static PortalFileDocument readDocument(Path path) {
        if (!Files.exists(path)) {
            return null;
        }

        try {
            String json = Files.readString(path);
            if (json.isBlank()) {
                return null;
            }
            return GSON.fromJson(json, PortalFileDocument.class);
        } catch (IOException | JsonParseException exception) {
            System.err.println("[Protocol Portals] Failed to read portal placements from '" + path + "': " + exception.getMessage());
            return null;
        }
    }

    private static StoredPortal toStoredPortal(String contextKey, PortalInstance portal) {
        StoredPortal out = new StoredPortal();
        out.contextKey = contextKey;
        out.sceneName = portal.sceneName();
        out.serverAddress = portal.serverAddress();
        out.center = VecData.from(portal.center());
        out.normal = VecData.from(portal.normal());
        out.right = VecData.from(portal.right());
        out.up = VecData.from(portal.up());
        out.sceneAnchor = VecData.from(portal.sceneAnchor());
        out.skyColor = VecData.from(portal.skyColor());
        out.width = portal.width();
        out.height = portal.height();
        return out;
    }

    private static Vec3d fromVec3(@Nullable VecData vec, Vec3d fallback) {
        if (vec == null) {
            return fallback;
        }
        if (!Double.isFinite(vec.x) || !Double.isFinite(vec.y) || !Double.isFinite(vec.z)) {
            return fallback;
        }
        return new Vec3d(vec.x, vec.y, vec.z);
    }

    private static String normalizeServerAddress(String rawAddress) {
        String trimmed = rawAddress.trim().toLowerCase(Locale.ROOT);
        if (!ServerAddress.isValid(trimmed)) {
            return sanitizeContextValue(trimmed);
        }

        ServerAddress parsed = ServerAddress.parse(trimmed);
        String host = parsed.getAddress().toLowerCase(Locale.ROOT);
        int port = parsed.getPort();
        return port == 25565 ? host : host + ":" + port;
    }

    private static String sanitizeFileName(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if ((ch >= 'a' && ch <= 'z')
                    || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '.'
                    || ch == '_'
                    || ch == '-') {
                out.append(ch);
            } else {
                out.append('_');
            }
        }
        return out.toString();
    }

    private static String sanitizeContextValue(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return "unknown";
        }
        return value.replace('\n', '_').replace('\r', '_');
    }

    private static String computeFingerprint(List<PortalInstance> portals) {
        List<PortalInstance> ordered = new ArrayList<>(portals);
        ordered.sort(Comparator
                .comparing(PortalInstance::sceneName)
                .thenComparing(portal -> portal.serverAddress() == null ? "" : portal.serverAddress())
                .thenComparing(portal -> String.format(Locale.ROOT, "%.3f,%.3f,%.3f", portal.center().x, portal.center().y, portal.center().z))
        );

        StringBuilder builder = new StringBuilder(ordered.size() * 160);
        for (PortalInstance portal : ordered) {
            builder.append(portal.sceneName()).append('|');
            builder.append(portal.serverAddress() == null ? "" : portal.serverAddress()).append('|');
            appendVec(builder, portal.center());
            appendVec(builder, portal.sceneAnchor());
            appendVec(builder, portal.normal());
            appendVec(builder, portal.right());
            appendVec(builder, portal.up());
            builder.append(String.format(Locale.ROOT, "%.3f|%.3f|", portal.width(), portal.height()));
        }
        return builder.toString();
    }

    private static void appendVec(StringBuilder out, Vec3d vec) {
        out.append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f|", vec.x, vec.y, vec.z));
    }

    private record StorageContext(String contextKey, Path filePath) {
    }

    private static final class PortalFileDocument {
        int version = FILE_VERSION;
        List<StoredPortal> portals = new ArrayList<>();
    }

    private static final class StoredPortal {
        String contextKey;
        String sceneName;
        String serverAddress;
        VecData center;
        VecData normal;
        VecData right;
        VecData up;
        VecData sceneAnchor;
        VecData skyColor;
        double width;
        double height;
    }

    private static final class VecData {
        double x;
        double y;
        double z;

        static VecData from(Vec3d vec) {
            VecData data = new VecData();
            data.x = vec.x;
            data.y = vec.y;
            data.z = vec.z;
            return data;
        }
    }
}
