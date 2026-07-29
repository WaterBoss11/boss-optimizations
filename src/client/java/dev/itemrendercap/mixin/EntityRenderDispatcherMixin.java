package dev.itemrendercap.mixin;

import dev.itemrendercap.RenderCap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The one hook this mod has.
 *
 * <p>{@code LevelExtractor} calls {@link EntityRenderDispatcher#shouldRender} for every entity
 * each frame and skips extracting a render state for anything that answers false. Returning
 * false here therefore drops the item entity out of the render pass entirely - before its
 * render state is even built, so it costs nothing rather than being drawn and discarded - while
 * leaving the entity itself completely untouched.
 */
@Mixin(EntityRenderDispatcher.class)
public class EntityRenderDispatcherMixin {
	@Inject(
			method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private void itemrendercap$capItemEntities(Entity entity, Frustum frustum, double cameraX, double cameraY,
			double cameraZ, CallbackInfoReturnable<Boolean> cir) {
		if (!RenderCap.allowRender(entity)) {
			cir.setReturnValue(false);
		}
	}
}
