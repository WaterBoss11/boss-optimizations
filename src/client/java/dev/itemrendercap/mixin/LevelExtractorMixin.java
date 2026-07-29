package dev.itemrendercap.mixin;

import dev.itemrendercap.RenderCap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundary marker.
 *
 * <p>{@code extract} runs once per frame and is the caller that eventually reaches
 * {@code extractVisibleEntities -> isEntityVisible -> EntityRenderDispatcher.shouldRender}.
 * Marking the selection set stale here, rather than on a wall-clock timer, is what guarantees
 * every item entity in a single frame is judged against the same set.
 *
 * <p>Read-only hook: it does not cancel, modify arguments, or touch any state Minecraft owns.
 */
@Mixin(LevelExtractor.class)
public class LevelExtractorMixin {
	@Inject(
			method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V",
			at = @At("HEAD")
	)
	private void itemrendercap$beginFrame(DeltaTracker deltaTracker, Camera camera, float partialTick,
			CallbackInfo ci) {
		RenderCap.beginFrame();
	}
}
