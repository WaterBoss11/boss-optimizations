package dev.itemrendercap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * The grouping and selection logic, deliberately free of any Minecraft types so it can be
 * exercised directly by tests across simulated frames. {@code RenderCap} is only an adapter
 * that feeds real entities into this.
 *
 * <p>Usage per frame: {@link #begin}, then {@link #offer} for every item entity, then
 * {@link #finish}. After that {@link #isVisible} answers for the whole frame.
 *
 * <h2>Why highest ID wins</h2>
 * Entity IDs come from {@code ServerLevel.ENTITY_COUNTER}, an {@code AtomicInteger} advanced by
 * {@code incrementAndGet()}, so they increase monotonically: a freshly dropped item always has a
 * higher ID than everything already on the ground. Selecting the <em>lowest</em> IDs therefore
 * permanently starves new drops in any group that is already full - they stay invisible for
 * their whole toss arc and then pop into existence once an older item leaves the group.
 * Selecting the <em>highest</em> IDs inverts that: the item you just threw is always drawn, and
 * the cap falls on the old settled pile instead, which is what you actually want to hide.
 *
 * <p>ID order is still completely stable frame to frame - an entity's ID never changes - so this
 * keeps the no-flicker property that picking by distance-to-camera would have destroyed.
 */
public final class ItemGroupSelector {
	/** Sentinel for "no crosshair target this frame". */
	public static final int NO_ID = -1;

	private final Long2ObjectOpenHashMap<IntArrayList> buckets = new Long2ObjectOpenHashMap<>();
	private final IntOpenHashSet visible = new IntOpenHashSet();
	private final IntOpenHashSet previousVisible = new IntOpenHashSet();
	private final IntOpenHashSet offered = new IntOpenHashSet();

	private int maxPerGroup;
	private double cellSize;

	// Diagnostics for the last completed frame.
	private int appearedCount;
	private int deselectedCount;
	private int emptyGroupCount;

	public void begin(int maxPerGroup, double cellSize) {
		if (cellSize <= 0.0D) {
			throw new IllegalArgumentException("cellSize must be positive, got " + cellSize);
		}

		this.maxPerGroup = Math.max(0, maxPerGroup);
		this.cellSize = cellSize;

		buckets.clear();
		offered.clear();
	}

	public void offer(int id, double x, double y, double z) {
		offered.add(id);

		if (maxPerGroup <= 0) {
			return;
		}

		long key = cellKey(x, y, z, cellSize);
		IntArrayList kept = buckets.get(key);

		if (kept == null) {
			kept = new IntArrayList(maxPerGroup + 1);
			buckets.put(key, kept);
		}

		keepHighest(kept, id, maxPerGroup);
	}

	/**
	 * @param forcedId an entity that must render regardless of its group, or {@link #NO_ID}.
	 */
	public void finish(int forcedId) {
		visible.clear();
		emptyGroupCount = 0;

		ObjectIterator<IntArrayList> groups = buckets.values().iterator();

		while (groups.hasNext()) {
			IntArrayList kept = groups.next();

			// A non-empty group must always contribute at least one item. If this ever trips,
			// the selection logic is dropping whole groups - exactly the failure mode where
			// items "should be in a rendered group" but never appear.
			if (kept.isEmpty()) {
				emptyGroupCount++;
				continue;
			}

			visible.addAll(kept);
		}

		if (forcedId != NO_ID) {
			visible.add(forcedId);
		}

		computeChurn();

		previousVisible.clear();
		previousVisible.addAll(visible);
	}

	/**
	 * Splits visibility changes into the two cases that mean very different things:
	 * <ul>
	 * <li><b>appeared</b> - newly visible. Expected and harmless (new drops, items entering view).
	 * <li><b>deselected</b> - the item still exists this frame but stopped being drawn. This is
	 * the instability metric. In a static scene it must be zero; anything else is flicker.
	 * </ul>
	 */
	private void computeChurn() {
		appearedCount = 0;
		deselectedCount = 0;

		IntIterator current = visible.iterator();

		while (current.hasNext()) {
			if (!previousVisible.contains(current.nextInt())) {
				appearedCount++;
			}
		}

		IntIterator previous = previousVisible.iterator();

		while (previous.hasNext()) {
			int id = previous.nextInt();

			// Only count it as instability if the entity is still here. An item that was picked
			// up or despawned legitimately stops rendering and is not churn.
			if (offered.contains(id) && !visible.contains(id)) {
				deselectedCount++;
			}
		}
	}

	public boolean isVisible(int id) {
		return visible.contains(id);
	}

	public int groupCount() {
		return buckets.size();
	}

	public int offeredCount() {
		return offered.size();
	}

	public int visibleCount() {
		return visible.size();
	}

	public int hiddenCount() {
		return offered.size() - visibleCount();
	}

	/** Items newly drawn this frame that were not drawn last frame. */
	public int appearedCount() {
		return appearedCount;
	}

	/** Items that still exist but stopped being drawn. Should be 0 in a static scene. */
	public int deselectedCount() {
		return deselectedCount;
	}

	/** Non-empty groups that contributed nothing. Should always be 0. */
	public int emptyGroupCount() {
		return emptyGroupCount;
	}

	/** Largest group size before the cap was applied is not tracked; this is post-cap. */
	public int largestGroupSize() {
		int largest = 0;
		ObjectIterator<IntArrayList> groups = buckets.values().iterator();

		while (groups.hasNext()) {
			largest = Math.max(largest, groups.next().size());
		}

		return largest;
	}

	public void reset() {
		buckets.clear();
		visible.clear();
		previousVisible.clear();
		offered.clear();
		appearedCount = 0;
		deselectedCount = 0;
		emptyGroupCount = 0;
	}

	/** Inserts {@code id} into a descending list, keeping at most {@code max} entries. */
	static void keepHighest(IntArrayList kept, int id, int max) {
		int size = kept.size();

		// List is descending, so the last element is the smallest one currently kept.
		if (size >= max && id <= kept.getInt(size - 1)) {
			return;
		}

		int index = 0;

		while (index < size && kept.getInt(index) > id) {
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
	static long cellKey(double x, double y, double z, double cellSize) {
		long cx = (long) Math.floor(x / cellSize);
		long cy = (long) Math.floor(y / cellSize);
		long cz = (long) Math.floor(z / cellSize);

		return cx * 0x9E3779B97F4A7C15L ^ cy * 0xC2B2AE3D27D4EB4FL ^ cz * 0x165667B19E3779F9L;
	}
}
