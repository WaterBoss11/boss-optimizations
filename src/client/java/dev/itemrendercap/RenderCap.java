package dev.itemrendercap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Decides which item entities are allowed to be drawn.
 *
 * <p>Item entities are bucketed into a uniform grid whose cell size is the configured group
 * radius. Within each cell only the lowest N entity IDs survive. Entity ID is used as the
 * tie-break rather than distance-to-camera precisely because it does not change as the player
 * moves, so the surviving set is stable and the pile does not shimmer.
 *
 * <p>The set is rebuilt at most once every {@value #REBUILD_INTERVAL_MS} ms (one tick) and is
 * held constant in between, so every entity in a given frame sees a consistent answer.
 *
 * <p>This only ever answers "draw or don't draw". Nothing here touches entity state, ticking,
 * pickup or despawning - the entities are all still there, they are just not submitted to the
 * renderer.
 */
public final class RenderCap {
	private static final long REBUILD_INTERVAL_MS = 50L;
	private static final long REBUILD_INTERVAL_NS = REBUILD_INTERVAL_MS * 1_000_000L;

	/** How far along the crosshair ray we look for an item entity to exempt. */
	private static final double LOOK_RANGE = 32.0D;

	/** Dropped items have a tiny hitbox; widen it a little so aiming at one is not fiddly. */
	private static final double LOOK_TOLERANCE = 0.25D;

	private static final IntOpenHashSet VISIBLE = new IntOpenHashSet();
	private static final Long2ObjectOpenHashMap<IntArrayList> BUCKETS = new Long2ObjectOpenHashMap<>();

	private static ClientLevel lastLevel;
	private static long lastRebuildNs;
	private static boolean built;

	private RenderCap() {
	}

	/**
	 * @return false only for item entities that the cap is currently hiding.
	 */
	public static boolean allowRender(Entity entity) {
		if (!(entity instanceof ItemEntity)) {
			return true;
		}

		ItemRenderCapConfig config = ItemRenderCapConfig.get();

		if (!config.enabled) {
			return true;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;

		if (level == null) {
			return true;
		}

		refresh(minecraft, level, config);
		return VISIBLE.contains(entity.getId());
	}

	private static void refresh(Minecraft minecraft, ClientLevel level, ItemRenderCapConfig config) {
		long now = System.nanoTime();

		if (built && level == lastLevel && now - lastRebuildNs < REBUILD_INTERVAL_NS) {
			return;
		}

		lastLevel = level;
		lastRebuildNs = now;
		built = true;
		rebuild(minecraft, level, config);
	}

	private static void rebuild(Minecraft minecraft, ClientLevel level, ItemRenderCapConfig config) {
		VISIBLE.clear();
		BUCKETS.clear();

		final int max = config.maxRenderedPerGroup;
		final double cell = config.groupRadius;

		Entity camera = minecraft.getCameraEntity();
		Vec3 eye = null;
		Vec3 reach = null;

		if (camera != null) {
			eye = camera.getEyePosition(1.0F);
			reach = eye.add(camera.getViewVector(1.0F).scale(LOOK_RANGE));
		}

		int lookedAt = -1;
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

			if (max > 0) {
				long key = cellKey(item.position(), cell);
				IntArrayList kept = BUCKETS.get(key);

				if (kept == null) {
					kept = new IntArrayList(max + 1);
					BUCKETS.put(key, kept);
				}

				keepLowest(kept, item.getId(), max);
			}
		}

		for (IntArrayList kept : BUCKETS.values()) {
			VISIBLE.addAll(kept);
		}

		BUCKETS.clear();

		// The crosshair target always renders, even if its group is already full.
		if (lookedAt != -1) {
			VISIBLE.add(lookedAt);
		}

		// Vanilla never picks item entities (they are not pickable), but another mod may have
		// made them so - honour that too rather than second-guessing it.
		if (minecraft.hitResult instanceof EntityHitResult entityHit
				&& entityHit.getEntity() instanceof ItemEntity picked) {
			VISIBLE.add(picked.getId());
		}
	}

	/** Inserts {@code id} into the ascending list, keeping at most {@code max} entries. */
	private static void keepLowest(IntArrayList kept, int id, int max) {
		int size = kept.size();

		if (size >= max && id >= kept.getInt(size - 1)) {
			return;
		}

		int index = 0;

		while (index < size && kept.getInt(index) < id) {
			index++;
		}

		kept.add(index, id);

		if (kept.size() > max) {
			kept.removeInt(kept.size() - 1);
		}
	}

	/**
	 * Mixes the three grid coordinates into one long. A hash rather than a bit-packing because
	 * cell coordinates at small radii overflow the 64 bits a packed layout would need.
	 */
	private static long cellKey(Vec3 pos, double cell) {
		long x = (long) Math.floor(pos.x / cell);
		long y = (long) Math.floor(pos.y / cell);
		long z = (long) Math.floor(pos.z / cell);

		return x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
	}
}
