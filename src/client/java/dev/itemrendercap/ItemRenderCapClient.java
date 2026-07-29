package dev.itemrendercap;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. All this mod does is load its config; the actual work happens in
 * {@link RenderCap}, driven by a single mixin on the entity render-culling check.
 */
public final class ItemRenderCapClient implements ClientModInitializer {
	public static final String MOD_ID = "itemrendercap";
	public static final Logger LOGGER = LoggerFactory.getLogger("Item Render Cap");

	@Override
	public void onInitializeClient() {
		ItemRenderCapConfig.load();
	}
}
