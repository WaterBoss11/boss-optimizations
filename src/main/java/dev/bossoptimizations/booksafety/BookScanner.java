package dev.bossoptimizations.booksafety;

/**
 * Incremental, early-bailing budget tracker for scanning a book.
 *
 * <p>Free of Minecraft types on purpose: the caller drives it while walking real book content,
 * and tests drive it directly to simulate book shapes that would be awkward to construct for
 * real.
 *
 * <h2>Why it bails early instead of measuring then judging</h2>
 * Measuring a hostile book in full would perform exactly the work being defended against. Every
 * method here returns {@code false} the moment a limit is exceeded, and the caller is expected
 * to stop immediately. In particular {@link #enterNode()} refuses <em>before</em> the caller
 * recurses, so a caller that honours the return value can never recurse deeper than
 * {@code maxNestingDepth} - which is what keeps a nesting bomb from overflowing the stack
 * during the very check meant to catch it.
 *
 * <p>Usage: {@link #beginPage()} per page, {@link #enterNode()}/{@link #exitNode()} around each
 * text node, {@link #addText(int)} for literal text, then {@link #finish()}.
 */
public final class BookScanner {
	private final BookSafetyConfig limits;

	private int pages;
	private int totalCharacters;
	private int currentPageCharacters;
	private int nodes;
	private int depth;
	private int maxDepthSeen;

	private BookScanReport.Verdict verdict = BookScanReport.Verdict.SAFE;

	public BookScanner(BookSafetyConfig limits) {
		this.limits = limits;
	}

	/** @return false if the book already has too many pages and scanning must stop. */
	public boolean beginPage() {
		if (aborted()) {
			return false;
		}

		if (pages >= limits.maxPages) {
			return fail(BookScanReport.Verdict.TOO_MANY_PAGES);
		}

		pages++;
		currentPageCharacters = 0;
		depth = 0;
		return true;
	}

	/** @return false if this node would exceed the depth or node budget. Do not recurse then. */
	public boolean enterNode() {
		if (aborted()) {
			return false;
		}

		if (nodes >= limits.maxNodes) {
			return fail(BookScanReport.Verdict.TOO_MANY_NODES);
		}

		if (depth >= limits.maxNestingDepth) {
			return fail(BookScanReport.Verdict.TOO_DEEP);
		}

		nodes++;
		depth++;
		maxDepthSeen = Math.max(maxDepthSeen, depth);
		return true;
	}

	public void exitNode() {
		if (depth > 0) {
			depth--;
		}
	}

	/** @return false if this text would push the page or the book over budget. */
	public boolean addText(int length) {
		if (aborted()) {
			return false;
		}

		if (length < 0) {
			return true;
		}

		// Accumulate with longs so a hostile length cannot wrap an int into a passing value.
		long page = (long) currentPageCharacters + length;
		long total = (long) totalCharacters + length;

		if (page > limits.maxPageCharacters) {
			return fail(BookScanReport.Verdict.PAGE_TOO_LARGE);
		}

		if (total > limits.maxTotalCharacters) {
			return fail(BookScanReport.Verdict.TOTAL_TOO_LARGE);
		}

		currentPageCharacters = (int) page;
		totalCharacters = (int) total;
		return true;
	}

	public boolean aborted() {
		return verdict != BookScanReport.Verdict.SAFE;
	}

	public BookScanReport finish() {
		return new BookScanReport(verdict, pages, totalCharacters, maxDepthSeen, nodes);
	}

	private boolean fail(BookScanReport.Verdict reason) {
		verdict = reason;
		return false;
	}
}
