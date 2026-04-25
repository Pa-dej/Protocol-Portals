package padej.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalManager;
import padej.client.screen.ProtocolPortalsScreen;
import padej.client.scene.SceneRepository;
import padej.client.scene.SceneSnapshot;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletionException;

public final class ProtocolPortalsCommand {
    private final SceneRepository sceneRepository;
    private final PortalManager portalManager;

    public ProtocolPortalsCommand(SceneRepository sceneRepository, PortalManager portalManager) {
        this.sceneRepository = sceneRepository;
        this.portalManager = portalManager;
    }

    public void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("pp")
                        .then(ClientCommandManager.literal("create")
                                .then(ClientCommandManager.literal("snapshot")
                                        .then(ClientCommandManager.argument("fileName", StringArgumentType.word())
                                                .executes(this::createSnapshot)))
                                .then(ClientCommandManager.literal("portal")
                                        .then(ClientCommandManager.argument("fileName", StringArgumentType.word())
                                                .executes(this::createPortal)
                                                .then(ClientCommandManager.argument("serverAddress", StringArgumentType.word())
                                                        .executes(this::createPortalWithServerAddress))))
                                .then(ClientCommandManager.literal("debug-plane")
                                        .executes(this::createDebugPlane)))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.literal("portal")
                                        .executes(this::removeNearestPortal)
                                        .then(ClientCommandManager.argument("fileName", StringArgumentType.word())
                                                .executes(this::removePortalByName))))
                        .then(ClientCommandManager.literal("settings")
                                .then(ClientCommandManager.literal("capture-radius")
                                        .executes(this::showCaptureRadiusSetting)
                                        .then(ClientCommandManager.literal("auto")
                                                .executes(this::setCaptureRadiusAuto))
                                        .then(ClientCommandManager.argument(
                                                        "chunks",
                                                        IntegerArgumentType.integer(
                                                                sceneRepository.minCaptureChunkRadius(),
                                                                sceneRepository.maxCaptureChunkRadius()))
                                                .executes(this::setCaptureRadiusManual))))
                        .then(ClientCommandManager.literal("menu")
                                .executes(this::openMenu))
        ));
    }

    private int openMenu(CommandContext<FabricClientCommandSource> context) {
        MinecraftClient client = context.getSource().getClient();
        client.setScreen(new ProtocolPortalsScreen(client.currentScreen, sceneRepository, portalManager));
        return 1;
    }

    private int showCaptureRadiusSetting(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        int manual = sceneRepository.manualCaptureChunkRadius();
        int effective = sceneRepository.effectiveCaptureChunkRadius(client);
        if (sceneRepository.isManualCaptureChunkRadiusEnabled()) {
            source.sendFeedback(Text.literal("Capture radius mode: manual (" + manual + " chunks)."));
        } else {
            int gameValue = client.options != null && client.options.getViewDistance() != null
                    ? client.options.getViewDistance().getValue()
                    : effective;
            source.sendFeedback(Text.literal("Capture radius mode: auto (game view distance " + gameValue
                    + ", effective " + effective + " chunks)."));
        }
        return 1;
    }

    private int setCaptureRadiusAuto(CommandContext<FabricClientCommandSource> context) {
        sceneRepository.setCaptureChunkRadiusAuto();
        return showCaptureRadiusSetting(context);
    }

    private int setCaptureRadiusManual(CommandContext<FabricClientCommandSource> context) {
        int chunks = IntegerArgumentType.getInteger(context, "chunks");
        sceneRepository.setCaptureChunkRadiusManual(chunks);
        return showCaptureRadiusSetting(context);
    }

    private int createSnapshot(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fileName = StringArgumentType.getString(context, "fileName");

        if (!sceneRepository.isValidFileName(fileName)) {
            source.sendError(SceneRepository.invalidNameText(fileName));
            return 0;
        }

        MinecraftClient client = source.getClient();
        if (client.player == null || client.world == null) {
            source.sendError(Text.literal("You must be in world to capture snapshot."));
            return 0;
        }

        source.sendFeedback(Text.literal("Starting snapshot capture '" + fileName + "'..."));
        try {
            SceneRepository.CaptureStartResult start = sceneRepository.startCapture(
                    client,
                    fileName,
                    result -> source.sendFeedback(Text.literal("Snapshot '" + result.sceneName() + "' saved ("
                            + result.blockCount() + " blocks, " + result.lightSampleCount()
                            + " light samples, " + result.chunkCount() + "/" + result.requestedChunkCount()
                            + " chunks, skipped unloaded " + result.skippedUnloadedChunks() + ", radius "
                            + result.horizontalRadius() + "x" + result.verticalRadius() + "x"
                            + result.horizontalRadius() + ") to " + result.scenePath())),
                    error -> source.sendError(Text.literal(error))
            );
            source.sendFeedback(Text.literal("Capture scheduled for " + start.totalChunks()
                    + " chunks (radius " + (start.horizontalRadius() / 16) + " chunks). "
                    + "Processing loaded chunks per tick: " + 2
                    + ", unloaded probes per tick: " + 24 + "."));
            return 1;
        } catch (IOException exception) {
            source.sendError(Text.literal("Snapshot creation failed: " + exception.getMessage()));
            return 0;
        }
    }

    private int createPortal(CommandContext<FabricClientCommandSource> context) {
        return createPortal(context, null);
    }

    private int createPortalWithServerAddress(CommandContext<FabricClientCommandSource> context) {
        return createPortal(context, StringArgumentType.getString(context, "serverAddress"));
    }

    private int createPortal(CommandContext<FabricClientCommandSource> context, @Nullable String targetServerAddress) {
        FabricClientCommandSource source = context.getSource();
        MinecraftClient client = source.getClient();
        String fileName = StringArgumentType.getString(context, "fileName");
        if (!sceneRepository.isValidFileName(fileName)) {
            source.sendError(SceneRepository.invalidNameText(fileName));
            return 0;
        }
        String normalizedServerAddress = normalizeAndValidateServerAddress(targetServerAddress, source);
        if (targetServerAddress != null && normalizedServerAddress == null) {
            return 0;
        }

        if (client.player == null) {
            source.sendError(Text.literal("You must be in world to create portal."));
            return 0;
        }

        Optional<SceneSnapshot> scene = sceneRepository.load(fileName);
        if (scene.isEmpty()) {
            source.sendError(Text.literal("Scene '" + fileName + "' not found. Create it with /pp create snapshot " + fileName));
            return 0;
        }

        String normalizedSceneName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedServerAddress == null) {
            source.sendFeedback(Text.literal("Preparing portal scene '" + fileName + "' on worker threads..."));
        } else {
            source.sendFeedback(Text.literal("Preparing portal scene '" + fileName
                    + "' for server '" + normalizedServerAddress + "'..."));
        }
        portalManager.createPortalAsync(client, client.player, normalizedSceneName, scene.get(), normalizedServerAddress)
                .thenAccept(portal -> client.execute(() -> source.sendFeedback(Text.literal(
                        "Portal created for scene '" + fileName + "' at "
                                + formatVec(portal.center().x, portal.center().y, portal.center().z)
                                + ". Render blocks: " + portal.renderBlocks().size()
                                + (portal.serverAddress() == null ? "" : ". Target server: " + portal.serverAddress())
                ))))
                .exceptionally(throwable -> {
                    Throwable cause = unwrapCompletionCause(throwable);
                    String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                    client.execute(() -> source.sendError(Text.literal("Portal creation failed: " + message)));
                    return null;
                });
        return 1;
    }

    private int createDebugPlane(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getClient().player == null) {
            source.sendError(Text.literal("You must be in world to create debug plane."));
            return 0;
        }

        var plane = portalManager.createDebugPlane(source.getClient().player);
        source.sendFeedback(Text.literal("Debug plane created at "
                + formatVec(plane.center().x, plane.center().y, plane.center().z)
                + ", size 3x3."));
        return 1;
    }

    private int removeNearestPortal(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        if (source.getClient().player == null) {
            source.sendError(Text.literal("You must be in world to remove portal."));
            return 0;
        }

        Optional<PortalInstance> removed = portalManager.removeNearestPortal(source.getClient().player.getPos());
        if (removed.isEmpty()) {
            source.sendError(Text.literal("No portals to remove."));
            return 0;
        }

        PortalInstance portal = removed.get();
        source.sendFeedback(Text.literal("Removed nearest portal for scene '" + portal.sceneName() + "' at "
                + formatVec(portal.center().x, portal.center().y, portal.center().z)));
        return 1;
    }

    private int removePortalByName(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fileName = StringArgumentType.getString(context, "fileName");
        if (!sceneRepository.isValidFileName(fileName)) {
            source.sendError(SceneRepository.invalidNameText(fileName));
            return 0;
        }

        if (source.getClient().player == null) {
            source.sendError(Text.literal("You must be in world to remove portal."));
            return 0;
        }

        String sceneName = fileName.toLowerCase(Locale.ROOT);
        Optional<PortalInstance> removed = portalManager.removePortalBySceneName(sceneName, source.getClient().player.getPos());
        if (removed.isEmpty()) {
            source.sendError(Text.literal("No portal found for scene '" + sceneName + "'."));
            return 0;
        }

        PortalInstance portal = removed.get();
        source.sendFeedback(Text.literal("Removed portal for scene '" + portal.sceneName() + "' at "
                + formatVec(portal.center().x, portal.center().y, portal.center().z)));
        return 1;
    }

    private static String formatVec(double x, double y, double z) {
        return String.format("(%.2f, %.2f, %.2f)", x, y, z);
    }

    private static Throwable unwrapCompletionCause(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Nullable
    private static String normalizeAndValidateServerAddress(@Nullable String serverAddress, FabricClientCommandSource source) {
        if (serverAddress == null) {
            return null;
        }
        String value = serverAddress.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!ServerAddress.isValid(value)) {
            source.sendError(Text.literal("Invalid server address '" + value + "'. Use host or host:port."));
            return null;
        }
        return value;
    }
}
