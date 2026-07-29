package dev.bossoptimizations.booksafety;

import dev.bossoptimizations.BossOptimizationsClient;
import dev.bossoptimizations.BossOptimizationsConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.List;

/**
 * Walks real book content through {@link BookScanner}.
 *
 * <h2>What this actually defends against in 26.2</h2>
 * Vanilla 26.2 is already far better protected than older versions. Page text is capped at
 * 32,767 characters by {@code ComponentSerialization.flatRestrictedCodec}, NBT nesting stops at
 * {@code NbtAccounter.MAX_STACK_DEPTH} (512), NBT size stops at 2 MB, titles are capped at 32
 * characters, and book content is only ever read when the book is opened - the tooltip path
 * ({@code WrittenBookContent.addToTooltip}) reads nothing but the author and generation.
 *
 * <p>The gap this closes is page <em>count</em>. {@code WritableBookContent} caps unsigned books
 * at {@code MAX_PAGES = 100}, but {@code WrittenBookContent.STREAM_CODEC} builds its page list
 * with the no-argument {@code ByteBufCodecs.list()} rather than the {@code list(int)} overload,
 * so a signed book arriving over the network has no explicit page-count limit. The remaining
 * checks duplicate limits vanilla already enforces, deliberately, as defence in depth against
 * NBT-edited content, other mods constructing components directly, and future regressions.
 */
public final class BookSafety {
	private BookSafety() {
	}

	/**
	 * @return a report describing the book, or a SAFE report if the module is disabled or the
	 *         stack holds no book content.
	 */
	public static BookScanReport scan(ItemStack stack, boolean filtered) {
		BookSafetyConfig config = BossOptimizationsConfig.get().bookSafety;

		if (!config.enabled) {
			return new BookScanReport(BookScanReport.Verdict.SAFE, 0, 0, 0, 0);
		}

		BookScanner scanner = new BookScanner(config);
		WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);

		if (written != null) {
			scanWritten(written, filtered, scanner);
			return scanner.finish();
		}

		WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);

		if (writable != null) {
			scanWritable(writable, filtered, scanner);
		}

		return scanner.finish();
	}

	private static void scanWritten(WrittenBookContent content, boolean filtered, BookScanner scanner) {
		List<Component> pages = content.getPages(filtered);

		for (Component page : pages) {
			if (!scanner.beginPage()) {
				return;
			}

			if (!walk(page, scanner)) {
				return;
			}
		}
	}

	private static void scanWritable(WritableBookContent content, boolean filtered, BookScanner scanner) {
		// Unsigned pages are plain strings, so there is no tree to walk and no nesting risk.
		// Vanilla already caps these at 100 pages of 1024 characters; scanned anyway so the
		// module reports consistently for both book types.
		for (String page : content.getPages(filtered).toList()) {
			if (!scanner.beginPage()) {
				return;
			}

			if (!scanner.enterNode() || !scanner.addText(page.length())) {
				return;
			}

			scanner.exitNode();
		}
	}

	/**
	 * Depth-first walk of a page's text tree.
	 *
	 * <p>Recursion depth is bounded by {@code maxNestingDepth} because {@link
	 * BookScanner#enterNode()} refuses before we descend, and every caller returns immediately
	 * on false. That is what stops a nesting bomb from overflowing the stack inside the check.
	 *
	 * @return false if the scan hit a limit and must stop entirely.
	 */
	private static boolean walk(Component component, BookScanner scanner) {
		if (!scanner.enterNode()) {
			return false;
		}

		ComponentContents contents = component.getContents();

		if (contents instanceof PlainTextContents.LiteralContents literal) {
			if (!scanner.addText(literal.text().length())) {
				return false;
			}
		} else if (contents instanceof TranslatableContents translatable) {
			if (!scanner.addText(translatable.getKey().length())) {
				return false;
			}

			for (Object arg : translatable.getArgs()) {
				if (arg instanceof Component nested && !walk(nested, scanner)) {
					return false;
				}
			}
		}

		for (Component sibling : component.getSiblings()) {
			if (!walk(sibling, scanner)) {
				return false;
			}
		}

		scanner.exitNode();
		return true;
	}

	/** The page shown in place of a book that was refused. */
	public static Component warningPage(BookScanReport report) {
		return Component.empty()
				.append(Component.literal("Book blocked\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
				.append(Component.literal("Boss Optimizations refused to open this book because "
						+ report.verdict().description() + ".\n\n").withStyle(ChatFormatting.BLACK))
				.append(Component.literal("Its contents were not parsed, so nothing was rendered.\n\n")
						.withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal("Raise the limits in the bookSafety section of "
						+ "config/boss-optimizations.json if this was a real book.")
						.withStyle(ChatFormatting.DARK_GRAY));
	}

	public static void logRejection(BookScanReport report) {
		BookSafetyConfig config = BossOptimizationsConfig.get().bookSafety;

		if (!config.logRejections) {
			return;
		}

		BossOptimizationsClient.LOGGER.warn(
				"Refused to open a book: {} (scanned {} page(s), {} character(s), depth {}, {} node(s) before stopping)",
				report.verdict().description(), report.pages(), report.characters(), report.maxDepth(),
				report.nodes());
	}
}
