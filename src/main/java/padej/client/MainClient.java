package padej.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import padej.client.command.ProtocolPortalsCommand;
import padej.client.portal.PortalManager;
import padej.client.render.PortalSceneRenderer;
import padej.client.render.ProtocolPortalsShaders;
import padej.client.render.SnapshotCaptureHudRenderer;
import padej.client.scene.SceneRepository;

public class MainClient implements ClientModInitializer {
    private final SceneRepository sceneRepository = new SceneRepository();
    private final PortalManager portalManager = new PortalManager();

    @Override
    public void onInitializeClient() {
        ProtocolPortalsShaders.register();
        new ProtocolPortalsCommand(sceneRepository, portalManager).register();
        new PortalSceneRenderer(portalManager).register();
        new SnapshotCaptureHudRenderer(sceneRepository).register();
        ClientTickEvents.END_CLIENT_TICK.register(sceneRepository::tick);
    }
}
