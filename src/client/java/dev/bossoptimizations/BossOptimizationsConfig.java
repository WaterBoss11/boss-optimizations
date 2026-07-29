package dev.bossoptimizations;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.bossoptimizations.booksafety.BookSafetyConfig;
import dev.bossoptimizations.itemrendercap.ItemRenderCapConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Root config, stored at {@code config/boss-optimizations.json}. Each module gets its own
 * nested section so features can be added without disturbing existing settings.
 *
 * <p>Written back on load so the file always exists and always contains every current key,
 * including ones added by a newer version. Read at startup only.
 */
public final class BossOptimizationsConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
			FabricLoader.getInstance().getConfigDir().resolve("boss-optimizations.json");

	private static BossOptimizationsConfig instance = new BossOptimizationsConfig();

	public ItemRenderCapConfig itemRenderCap = new ItemRenderCapConfig();
	public BookSafetyConfig bookSafety = new BookSafetyConfig();

	public static BossOptimizationsConfig get() {
		return instance;
	}

	public static void load() {
		BossOptimizationsConfig loaded = new BossOptimizationsConfig();

		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
				BossOptimizationsConfig parsed = GSON.fromJson(reader, BossOptimizationsConfig.class);

				if (parsed != null) {
					loaded = parsed;
				}
			} catch (IOException | JsonSyntaxException e) {
				BossOptimizationsClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
			}
		}

		loaded.fillGaps();
		instance = loaded;
		save(loaded);
	}

	private static void save(BossOptimizationsConfig config) {
		try {
			Files.createDirectories(PATH.getParent());

			try (Writer writer = Files.newBufferedWriter(PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			BossOptimizationsClient.LOGGER.warn("Could not write {}", PATH, e);
		}
	}

	/**
	 * Gson leaves absent sections null, which is exactly what happens when an older config file
	 * predates a module. Recreate anything missing rather than NPE-ing later.
	 */
	private void fillGaps() {
		if (itemRenderCap == null) {
			itemRenderCap = new ItemRenderCapConfig();
		}

		if (bookSafety == null) {
			bookSafety = new BookSafetyConfig();
		}

		itemRenderCap.clamp();
		bookSafety.clamp();
	}
}
