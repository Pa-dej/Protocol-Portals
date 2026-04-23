package padej.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import padej.client.command.ProtocolPortalsCommand;
import padej.client.portal.PortalManager;
import padej.client.render.PortalSceneRenderer;
import padej.client.render.ProtocolPortalsShaders;
import padej.client.render.SnapshotCaptureHudRenderer;
import padej.client.screen.ProtocolPortalsScreen;
import padej.client.scene.SceneRepository;

public class MainClient implements ClientModInitializer {
    private final SceneRepository sceneRepository = new SceneRepository();
    private final PortalManager portalManager = new PortalManager();
    private KeyBinding openMenuKeyBinding;

    @Override
    public void onInitializeClient() {
        ProtocolPortalsShaders.register();
        new ProtocolPortalsCommand(sceneRepository, portalManager).register();
        new PortalSceneRenderer(portalManager).register();
        new SnapshotCaptureHudRenderer(sceneRepository).register();
        openMenuKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.protocol_portals.open_menu",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.protocol_portals"
        ));
        ClientTickEvents.END_CLIENT_TICK.register(sceneRepository::tick);
        ClientTickEvents.END_CLIENT_TICK.register(this::handleMenuHotkey);
    }

    private void handleMenuHotkey(MinecraftClient client) {
        if (openMenuKeyBinding == null) {
            return;
        }
        while (openMenuKeyBinding.wasPressed()) {
            if (client.player == null || client.world == null) {
                continue;
            }
            if (client.currentScreen != null) {
                continue;
            }
            client.setScreen(new ProtocolPortalsScreen(client.currentScreen, sceneRepository, portalManager));
        }
    }
}
