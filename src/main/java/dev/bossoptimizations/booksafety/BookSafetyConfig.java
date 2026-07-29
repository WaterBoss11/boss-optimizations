package dev.bossoptimizations.booksafety;

/**
 * Config section and threshold set for the book safety module. Deliberately free of Minecraft
 * types so the scanner and its tests can use it directly.
 *
 * <h2>Where the defaults come from</h2>
 * A book written in-game is bounded by vanilla at {@code WritableBookContent.MAX_PAGES = 100}
 * pages and {@code PAGE_EDIT_LENGTH = 1024} characters per page, so a legitimate hand-written
 * book tops out near 100k characters and a nesting depth of a handful. Every default here sits
 * several times above that, so real books - including generously formatted datapack ones - pass
 * untouched, while the shapes that exist only to hurt a client do not.
 */
public final class BookSafetyConfig {
	/** Master switch. Default on: this is purely defensive and costs a real book nothing. */
	public boolean enabled = true;

	/**
	 * Maximum pages. Vanilla caps <em>unsigned</em> books at 100 but does not cap page count on
	 * the network codec for signed books, so this is the one limit with no vanilla equivalent.
	 */
	public int maxPages = 512;

	/** Maximum characters in any single page. Matches vanilla's own per-page codec limit. */
	public int maxPageCharacters = 32767;

	/** Maximum characters across the whole book. A legitimate 100-page book is around 100k. */
	public int maxTotalCharacters = 500_000;

	/**
	 * Maximum nesting depth of a page's text tree. Real formatted text is a handful deep;
	 * vanilla's own NBT guard only stops at 512, which is far past the point of usefulness.
	 */
	public int maxNestingDepth = 64;

	/** Maximum total text nodes across the book, bounding wide-but-shallow trees. */
	public int maxNodes = 50_000;

	/** Log a warning naming the limit that tripped when a book is refused. */
	public boolean logRejections = true;

	public void clamp() {
		maxPages = Math.max(1, maxPages);
		maxPageCharacters = Math.max(1, maxPageCharacters);
		maxTotalCharacters = Math.max(1, maxTotalCharacters);
		// Depth must allow at least a root node plus a little nesting, and must stay well under
		// the JVM stack: the scanner's recursion depth is bounded by exactly this number.
		maxNestingDepth = Math.clamp(maxNestingDepth, 2, 512);
		maxNodes = Math.max(1, maxNodes);
	}
}
