package dev.bossoptimizations;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint for Boss Optimizations, a client-side performance and defensive mod.
 *
 * <p>Each feature lives in its own subpackage and owns a section of the config. Modules are
 * expected to be independently toggleable and to do nothing when disabled.
 *
 * <p>Current modules:
 * <ul>
 * <li>{@code itemrendercap} - caps how many dropped item entities are drawn on screen.
 * </ul>
 */
public final class BossOptimizationsClient implements ClientModInitializer {
	public static final String MOD_ID = "bossoptimizations";
	public static final Logger LOGGER = LoggerFactory.getLogger("Boss Optimizations");

	@Override
	public void onInitializeClient() {
		BossOptimizationsConfig.load();
	}
}
