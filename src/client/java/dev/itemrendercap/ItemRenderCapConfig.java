package dev.itemrendercap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Config, stored at {@code config/itemrendercap.json}. Written back on first launch so the
 * file exists to edit. Reloaded only at startup - this mod has no command or UI on purpose.
 */
public final class ItemRenderCapConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve(ItemRenderCapClient.MOD_ID + ".json");

	private static ItemRenderCapConfig instance = new ItemRenderCapConfig();

	/** Master switch. When false the mod does nothing at all. */
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
	 * Logs group formation and selection stats to the game log. Leave off for normal play; turn
	 * on to diagnose items that fail to render or flicker.
	 */
	public boolean debug = false;

	/**
	 * How often to emit a debug line, in frames. Frames where the selection looks unstable are
	 * always logged regardless of this interval.
	 */
	public int debugLogIntervalFrames = 60;

	public static ItemRenderCapConfig get() {
		return instance;
	}

	static void load() {
		ItemRenderCapConfig loaded = new ItemRenderCapConfig();

		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				ItemRenderCapConfig parsed = GSON.fromJson(reader, ItemRenderCapConfig.class);

				if (parsed != null) {
					loaded = parsed;
				}
			} catch (IOException | JsonSyntaxException e) {
				ItemRenderCapClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}

		loaded.clamp();
		instance = loaded;
		save(loaded);
	}

	private static void save(ItemRenderCapConfig config) {
		try {
			Files.createDirectories(PATH.getParent());

			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			ItemRenderCapClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	private void clamp() {
		maxRenderedPerGroup = Math.max(0, maxRenderedPerGroup);
		// A zero or negative radius would divide by zero when bucketing; the upper bound keeps
		// the grid coarse enough to stay meaningful.
		groupRadius = Math.clamp(groupRadius, 0.5D, 128.0D);
		debugLogIntervalFrames = Math.max(1, debugLogIntervalFrames);
	}
}
