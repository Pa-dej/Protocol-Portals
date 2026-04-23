package padej.client.render;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import padej.client.scene.SceneRepository;

public final class SnapshotCaptureHudRenderer {
    private static final int BAR_WIDTH = 220;
    private static final int BAR_HEIGHT = 10;

    private final SceneRepository sceneRepository;

    public SnapshotCaptureHudRenderer(SceneRepository sceneRepository) {
        this.sceneRepository = sceneRepository;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::render);
    }

    private void render(DrawContext drawContext, RenderTickCounter tickCounter) {
        SceneRepository.CaptureHudState state = sceneRepository.hudState();
        if (state == null) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.options.hudHidden || client.textRenderer == null) {
            return;
        }

        int x = (drawContext.getScaledWindowWidth() - BAR_WIDTH) / 2;
        int y = 12;
        int innerWidth = BAR_WIDTH - 2;
        int filled = Math.max(0, Math.min(innerWidth, Math.round(innerWidth * state.progress())));

        String title = "Snapshot '" + state.sceneName() + "' - " + stageLabel(state.stage());
        int percent = Math.round(state.progress() * 100.0F);
        String detail = state.detail() + " (" + percent + "%)";

        drawContext.drawText(client.textRenderer, title, x, y, 0xFFFFFF, true);
        drawContext.drawText(client.textRenderer, detail, x, y + 22, 0xD0D0D0, false);

        int barY = y + 10;
        drawContext.fill(x, barY, x + BAR_WIDTH, barY + BAR_HEIGHT, 0xA0000000);
        drawContext.fill(x + 1, barY + 1, x + 1 + filled, barY + BAR_HEIGHT - 1, stageColor(state.stage()));
        drawBorder(drawContext, x, barY, BAR_WIDTH, BAR_HEIGHT, 0xFF2C2C2C);
    }

    private static String stageLabel(SceneRepository.CaptureStage stage) {
        return stage == SceneRepository.CaptureStage.SAVING ? "saving" : "capturing";
    }

    private static int stageColor(SceneRepository.CaptureStage stage) {
        return stage == SceneRepository.CaptureStage.SAVING ? 0xFF4A90E2 : 0xFF65C466;
    }

    private static void drawBorder(DrawContext drawContext, int x, int y, int width, int height, int color) {
        drawContext.fill(x, y, x + width, y + 1, color);
        drawContext.fill(x, y + height - 1, x + width, y + height, color);
        drawContext.fill(x, y, x + 1, y + height, color);
        drawContext.fill(x + width - 1, y, x + width, y + height, color);
    }
}
