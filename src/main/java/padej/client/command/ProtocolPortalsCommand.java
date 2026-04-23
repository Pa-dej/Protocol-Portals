package padej.client.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import padej.client.portal.PortalInstance;
import padej.client.portal.PortalManager;
import padej.client.scene.SceneRepository;
import padej.client.scene.SceneSnapshot;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

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
                                                .executes(this::createPortal)))
                                .then(ClientCommandManager.literal("debug-plane")
                                        .executes(this::createDebugPlane)))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.literal("portal")
                                        .executes(this::removeNearestPortal)
                                        .then(ClientCommandManager.argument("fileName", StringArgumentType.word())
                                                .executes(this::removePortalByName))))
        ));
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
                    + " chunks. Processing loaded chunks per tick: " + 2
                    + ", unloaded probes per tick: " + 24 + "."));
            return 1;
        } catch (IOException exception) {
            source.sendError(Text.literal("Snapshot creation failed: " + exception.getMessage()));
            return 0;
        }
    }

    private int createPortal(CommandContext<FabricClientCommandSource> context) {
        FabricClientCommandSource source = context.getSource();
        String fileName = StringArgumentType.getString(context, "fileName");
        if (!sceneRepository.isValidFileName(fileName)) {
            source.sendError(SceneRepository.invalidNameText(fileName));
            return 0;
        }

        if (source.getClient().player == null) {
            source.sendError(Text.literal("You must be in world to create portal."));
            return 0;
        }

        Optional<SceneSnapshot> scene = sceneRepository.load(fileName);
        if (scene.isEmpty()) {
            source.sendError(Text.literal("Scene '" + fileName + "' not found. Create it with /pp create snapshot " + fileName));
            return 0;
        }

        PortalInstance portal = portalManager.createPortal(source.getClient().player, fileName.toLowerCase(Locale.ROOT), scene.get());
        source.sendFeedback(Text.literal("Portal created for scene '" + fileName + "' at "
                + formatVec(portal.center().x, portal.center().y, portal.center().z)
                + ". Render blocks: " + portal.renderBlocks().size()));
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
}
