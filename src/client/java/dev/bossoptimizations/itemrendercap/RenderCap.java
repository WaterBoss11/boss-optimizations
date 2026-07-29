package dev.bossoptimizations.itemrendercap;

import dev.bossoptimizations.BossOptimizationsClient;
import dev.bossoptimizations.BossOptimizationsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Adapter between Minecraft's render pipeline and {@link ItemGroupSelector}.
 *
 * <p>The selection set is rebuilt exactly once per frame, driven by {@link #beginFrame()} on
 * {@code LevelExtractor.extract}. It is <em>not</em> rebuilt on a timer: a timer expiring
 * partway through an extraction pass would swap the answer set mid-frame, so items checked
 * before the swap and items checked after it would be judged against different sets. That
 * produces exactly the "items sometimes don't render even though they should be in a rendered
 * group" symptom, and it is why the previous time-based version was wrong.
 *
 * <p>This only ever answers "draw or don't draw". Nothing here touches entity state, ticking,
 * pickup or despawning.
 */
public final class RenderCap {
	/** How far along the crosshair ray we look for an item entity to exempt. */
	private static final double LOOK_RANGE = 32.0D;

	/** Dropped items have a tiny hitbox; widen it a little so aiming at one is not fiddly. */
	private static final double LOOK_TOLERANCE = 0.25D;

	private static final ItemGroupSelector SELECTOR = new ItemGroupSelector();

	private static ClientLevel lastLevel;
	private static boolean frameDirty = true;
	private static boolean announced;
	private static long frameCounter;

	private RenderCap() {
	}

	/**
	 * Called at the head of every frame's extraction pass. Marks the selection set stale so it
	 * is rebuilt once, on the first item entity this frame, and then held constant until the
	 * next frame begins.
	 */
	public static void beginFrame() {
		frameDirty = true;
	}

	/**
	 * @return false only for item entities the cap is currently hiding.
	 */
	public static boolean allowRender(Entity entity) {
		if (!(entity instanceof ItemEntity)) {
			return true;
		}

		ItemRenderCapConfig config = BossOptimizationsConfig.get().itemRenderCap;

		if (!config.enabled) {
			return true;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null) {
			return true;
		}

		if (!announced) {
			announced = true;
			BossOptimizationsClient.LOGGER.info(
					"Active - mixin applied, first cull decision made (maxRenderedPerGroup={}, groupRadius={})",
					config.maxRenderedPerGroup, config.groupRadius);
		}

		if (frameDirty || level != lastLevel) {
			frameDirty = false;
			lastLevel = level;
			rebuild(minecraft, level, config);
		}

		return SELECTOR.isVisible(entity.getId());
	}

	private static void rebuild(Minecraft minecraft, ClientLevel level, ItemRenderCapConfig config) {
		frameCounter++;
		SELECTOR.begin(config.maxRenderedPerGroup, config.groupRadius);

		Entity camera = minecraft.getCameraEntity();
		Vec3 eye = null;
		Vec3 reach = null;

		if (camera != null) {
			eye = camera.getEyePosition(1.0F);
			reach = eye.add(camera.getViewVector(1.0F).scale(LOOK_RANGE));
		}

		int lookedAt = ItemGroupSelector.NO_ID;
		double lookedAtDistanceSq = Double.MAX_VALUE;

		for (Entity entity : level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item)) {
				continue;
			}

			if (eye != null) {
				AABB box = item.getBoundingBox().inflate(LOOK_TOLERANCE);
				Optional<Vec3> hit = box.clip(eye, reach);

				if (hit.isPresent()) {
					double distanceSq = eye.distanceToSqr(hit.get());

					if (distanceSq < lookedAtDistanceSq) {
						lookedAtDistanceSq = distanceSq;
						lookedAt = item.getId();
					}
				}
			}

			Vec3 pos = item.position();
			SELECTOR.offer(item.getId(), pos.x, pos.y, pos.z);
		}

		// Vanilla never picks item entities (they are not pickable), but another mod may have
		// made them so - honour that too rather than second-guessing it.
		if (lookedAt == ItemGroupSelector.NO_ID
				&& minecraft.hitResult instanceof EntityHitResult entityHit
				&& entityHit.getEntity() instanceof ItemEntity picked) {
			lookedAt = picked.getId();
		}

		SELECTOR.finish(lookedAt);

		if (config.debug) {
			logDiagnostics(config, lookedAt);
		}
	}

	private static void logDiagnostics(ItemRenderCapConfig config, int lookedAt) {
		int interval = Math.max(1, config.debugLogIntervalFrames);
		boolean unstable = SELECTOR.deselectedCount() > 0 || SELECTOR.emptyGroupCount() > 0;

		// Always log the moment something looks unstable, otherwise only every Nth frame.
		if (!unstable && frameCounter % interval != 0L) {
			return;
		}

		BossOptimizationsClient.LOGGER.info(
				"frame={} items={} groups={} visible={} hidden={} largestGroup={} appeared={} deselected={} emptyGroups={} crosshair={}",
				frameCounter,
				SELECTOR.offeredCount(),
				SELECTOR.groupCount(),
				SELECTOR.visibleCount(),
				SELECTOR.hiddenCount(),
				SELECTOR.largestGroupSize(),
				SELECTOR.appearedCount(),
				SELECTOR.deselectedCount(),
				SELECTOR.emptyGroupCount(),
				lookedAt == ItemGroupSelector.NO_ID ? "none" : lookedAt);

		if (unstable) {
			BossOptimizationsClient.LOGGER.warn(
					"Unstable selection this frame: {} item(s) still present but dropped from the render set, {} empty group(s). "
							+ "In a static scene both should be 0 - please report this with the surrounding log lines.",
					SELECTOR.deselectedCount(), SELECTOR.emptyGroupCount());
		}
	}
}
