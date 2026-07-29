package dev.itemrendercap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests exist because "the code looks right" was not good enough the first time. They
 * drive the selector across many simulated frames and assert properties that only show up over
 * time - stability, and that new drops are never starved.
 */
class ItemGroupSelectorTest {
	private static final double CELL = 4.0D;

	private record Item(int id, double x, double y, double z) {
	}

	private static void offerAll(ItemGroupSelector selector, List<Item> items) {
		for (Item item : items) {
			selector.offer(item.id(), item.x(), item.y(), item.z());
		}
	}

	private static List<Item> pile(int firstId, int count, double x, double y, double z) {
		List<Item> items = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			items.add(new Item(firstId + i, x, y, z));
		}

		return items;
	}

	// ---------------------------------------------------------------------------------------
	// Bug 1: new drops were starved because entity IDs increase monotonically.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("REGRESSION: the old lowest-ID policy starves a new drop in a full group")
	void oldLowestIdPolicyStarvesNewDrops() {
		// This reproduces the shipped v1.0.0 behaviour to prove the root cause is real.
		IntArrayList kept = new IntArrayList();

		for (int id = 1; id <= 5; id++) {
			keepLowest(kept, id, 5);
		}

		keepLowest(kept, 9001, 5); // freshly dropped item: highest ID, as the server always assigns

		assertFalse(kept.contains(9001),
				"old policy kept " + kept + " - the new drop is excluded, so its toss arc is never drawn");
		assertEquals(IntArrayList.of(1, 2, 3, 4, 5), kept);
	}

	@Test
	@DisplayName("FIX: the newest item always wins a slot in a full group")
	void newestItemAlwaysSelected() {
		ItemGroupSelector selector = new ItemGroupSelector();
		selector.begin(5, CELL);
		offerAll(selector, pile(1, 20, 0.5D, 64.0D, 0.5D));
		selector.offer(9001, 0.5D, 64.0D, 0.5D);
		selector.finish(ItemGroupSelector.NO_ID);

		assertTrue(selector.isVisible(9001), "the newest drop must render");
		assertEquals(5, selector.visibleCount());
	}

	@Test
	@DisplayName("FIX: a tossed item stays visible on every frame of its arc, over a full pile")
	void tossArcVisibleEveryFrame() {
		ItemGroupSelector selector = new ItemGroupSelector();

		// Two adjacent cells, both already saturated with older items.
		List<Item> cellA = pile(1, 20, 0.5D, 64.0D, 0.5D);
		List<Item> cellB = pile(100, 20, 5.5D, 64.0D, 0.5D);

		int frames = 40;

		for (int frame = 0; frame < frames; frame++) {
			// The thrown item flies from cell A across the boundary into cell B.
			double x = 0.5D + (7.0D * frame / (frames - 1));
			double y = 64.0D + Math.sin(Math.PI * frame / (frames - 1)) * 1.5D;

			selector.begin(5, CELL);
			offerAll(selector, cellA);
			offerAll(selector, cellB);
			selector.offer(9001, x, y, 0.5D);
			selector.finish(ItemGroupSelector.NO_ID);

			assertTrue(selector.isVisible(9001),
					"thrown item vanished on frame " + frame + " at x=" + x + " - toss animation would be lost");
		}
	}

	// ---------------------------------------------------------------------------------------
	// Bug 2: frame-to-frame stability.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("STABILITY: a static scene deselects nothing across 500 frames")
	void staticSceneIsPerfectlyStable() {
		ItemGroupSelector selector = new ItemGroupSelector();
		List<Item> items = scatter(400, 12345L);

		int totalDeselected = 0;
		int totalAppearedAfterFirstFrame = 0;

		for (int frame = 0; frame < 500; frame++) {
			selector.begin(5, CELL);
			offerAll(selector, items);
			selector.finish(ItemGroupSelector.NO_ID);

			if (frame > 0) {
				totalDeselected += selector.deselectedCount();
				totalAppearedAfterFirstFrame += selector.appearedCount();
			}
		}

		assertEquals(0, totalDeselected, "a motionless pile must never drop an item from the render set");
		assertEquals(0, totalAppearedAfterFirstFrame, "a motionless pile must never add an item either");
	}

	@Test
	@DisplayName("STABILITY: no non-empty group ever selects zero items")
	void everyGroupAlwaysSelectsAtLeastOne() {
		ItemGroupSelector selector = new ItemGroupSelector();
		Random random = new Random(99L);

		for (int frame = 0; frame < 200; frame++) {
			List<Item> items = scatter(300, random.nextLong());

			selector.begin(5, CELL);
			offerAll(selector, items);
			selector.finish(ItemGroupSelector.NO_ID);

			assertEquals(0, selector.emptyGroupCount(), "frame " + frame + " produced an empty group");

			// Independently verify: every occupied cell has at least one visible member.
			LongOpenHashSet cells = new LongOpenHashSet();
			LongOpenHashSet cellsWithVisible = new LongOpenHashSet();

			for (Item item : items) {
				long key = ItemGroupSelector.cellKey(item.x(), item.y(), item.z(), CELL);
				cells.add(key);

				if (selector.isVisible(item.id())) {
					cellsWithVisible.add(key);
				}
			}

			assertEquals(cells.size(), cellsWithVisible.size(),
					"frame " + frame + ": some occupied cell rendered nothing at all");
		}
	}

	@Test
	@DisplayName("STABILITY: items jittering on a cell boundary - measured, not assumed")
	void boundaryJitterChurnIsBounded() {
		ItemGroupSelector selector = new ItemGroupSelector();

		// Ten items parked exactly on the x=4.0 cell boundary, wobbling a few centimetres.
		int frames = 600;
		int totalDeselected = 0;

		for (int frame = 0; frame < frames; frame++) {
			selector.begin(5, CELL);

			for (int i = 0; i < 10; i++) {
				double wobble = Math.sin((frame + i * 7) * 0.35D) * 0.05D;
				selector.offer(i + 1, 4.0D + wobble, 64.0D, 0.5D);
			}

			selector.finish(ItemGroupSelector.NO_ID);

			if (frame > 0) {
				totalDeselected += selector.deselectedCount();
			}
		}

		double perFrame = (double) totalDeselected / (frames - 1);
		System.out.printf("boundary-jitter churn: %d deselections over %d frames (%.4f per frame)%n",
				totalDeselected, frames - 1, perFrame);

		// This is residual, not zero: an item that physically crosses into a neighbouring cell
		// legitimately re-competes there. It is bounded and only affects moving items.
		assertTrue(perFrame < 1.0D,
				"boundary churn averaged " + perFrame + " deselections/frame, which would be visible flicker");
	}

	// ---------------------------------------------------------------------------------------
	// Crosshair exemption and cap behaviour.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("the crosshair target renders even when it would lose its group slot")
	void crosshairTargetAlwaysRenders() {
		ItemGroupSelector selector = new ItemGroupSelector();
		selector.begin(5, CELL);
		offerAll(selector, pile(1, 50, 0.5D, 64.0D, 0.5D));
		selector.finish(3); // ID 3 is far from the newest and would otherwise be culled

		assertTrue(selector.isVisible(3), "crosshair target must always render");
		assertEquals(6, selector.visibleCount(), "cap of 5 plus the forced crosshair target");
	}

	@Test
	@DisplayName("maxPerGroup = 0 hides everything except the crosshair target")
	void zeroCapHidesAllButCrosshair() {
		ItemGroupSelector selector = new ItemGroupSelector();
		selector.begin(0, CELL);
		offerAll(selector, pile(1, 30, 0.5D, 64.0D, 0.5D));
		selector.finish(7);

		assertEquals(1, selector.visibleCount());
		assertTrue(selector.isVisible(7));
	}

	@Test
	@DisplayName("the cap is applied per group, not globally")
	void capIsPerGroup() {
		ItemGroupSelector selector = new ItemGroupSelector();
		selector.begin(5, CELL);
		offerAll(selector, pile(1, 20, 0.5D, 64.0D, 0.5D));
		offerAll(selector, pile(100, 20, 100.5D, 64.0D, 0.5D));
		offerAll(selector, pile(200, 20, 200.5D, 64.0D, 0.5D));
		selector.finish(ItemGroupSelector.NO_ID);

		assertEquals(3, selector.groupCount());
		assertEquals(15, selector.visibleCount(), "three groups of five");
		assertEquals(45, selector.hiddenCount());
	}

	@Test
	@DisplayName("a group smaller than the cap renders in full")
	void smallGroupRendersEntirely() {
		ItemGroupSelector selector = new ItemGroupSelector();
		selector.begin(5, CELL);
		offerAll(selector, pile(1, 3, 0.5D, 64.0D, 0.5D));
		selector.finish(ItemGroupSelector.NO_ID);

		assertEquals(3, selector.visibleCount());
		assertEquals(0, selector.hiddenCount());
	}

	@Test
	@DisplayName("picking an item up does not count as instability")
	void despawnIsNotCountedAsChurn() {
		ItemGroupSelector selector = new ItemGroupSelector();
		List<Item> items = pile(1, 10, 0.5D, 64.0D, 0.5D);

		selector.begin(5, CELL);
		offerAll(selector, items);
		selector.finish(ItemGroupSelector.NO_ID);

		// Item 10 was visible (highest ID) and is now picked up.
		assertTrue(selector.isVisible(10));

		selector.begin(5, CELL);
		offerAll(selector, items.subList(0, 9));
		selector.finish(ItemGroupSelector.NO_ID);

		assertEquals(0, selector.deselectedCount(),
				"an item that no longer exists must not be reported as unstable selection");
	}

	// ---------------------------------------------------------------------------------------

	private static List<Item> scatter(int count, long seed) {
		Random random = new Random(seed);
		List<Item> items = new ArrayList<>();

		for (int i = 0; i < count; i++) {
			items.add(new Item(i + 1,
					random.nextInt(60) + 0.5D,
					64.0D,
					random.nextInt(60) + 0.5D));
		}

		return items;
	}

	/** The shipped v1.0.0 policy, kept only so the regression test can demonstrate the bug. */
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
}
