package dev.bossoptimizations.itemrendercap;

/**
 * Config section for the item render cap module. Lives under {@code itemRenderCap} in
 * {@code config/boss-optimizations.json}.
 */
public final class ItemRenderCapConfig {
	/** Master switch for this module. When false the module does nothing at all. */
	public boolean enabled = true;

	/**
	 * How many item entities may be drawn per group. 0 hides every item except the one under
	 * your crosshair. Default 5 so a normal pile still looks like a pile.
	 */
	public int maxRenderedPerGroup = 5;

	/**
	 * Size of a group, in blocks. Item entities are bucketed into a grid of cubes this wide,
	 * and each bucket is capped independently.
	 */
	public double groupRadius = 4.0D;

	/**
	 * Logs group formation and selection stats. Leave off for normal play; turn on to diagnose
	 * items that fail to render or flicker.
	 */
	public boolean debug = false;

	/**
	 * How often to emit a debug line, in frames. Frames where the selection looks unstable are
	 * always logged regardless of this interval.
	 */
	public int debugLogIntervalFrames = 60;

	public void clamp() {
		maxRenderedPerGroup = Math.max(0, maxRenderedPerGroup);
		// A zero or negative radius would divide by zero when bucketing; the upper bound keeps
		// the grid coarse enough to stay meaningful.
		groupRadius = Math.clamp(groupRadius, 0.5D, 128.0D);
		debugLogIntervalFrames = Math.max(1, debugLogIntervalFrames);
	}
}
