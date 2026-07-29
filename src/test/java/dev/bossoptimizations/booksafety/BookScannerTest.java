package dev.bossoptimizations.booksafety;

import dev.bossoptimizations.booksafety.BookScanReport.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner is the part that has to be right: it decides whether hostile content is ever
 * parsed. These tests drive it directly so book shapes that would be awkward or dangerous to
 * build for real can be simulated exactly.
 *
 * <p>The recurring assertion is not just "it rejects" but "it rejects <em>early</em>" - a check
 * that measures a hostile book in full has already done the damage it was meant to prevent.
 */
class BookScannerTest {
	private static BookSafetyConfig limits() {
		BookSafetyConfig config = new BookSafetyConfig();
		config.clamp();
		return config;
	}

	/** Feeds a flat page of one node holding {@code characters} of text. */
	private static boolean simplePage(BookScanner scanner, int characters) {
		return scanner.beginPage() && scanner.enterNode() && scanner.addText(characters);
	}

	// ---------------------------------------------------------------------------------------
	// Normal books must pass untouched.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("a realistic hand-written book passes")
	void realisticBookIsSafe() {
		// Vanilla caps in-game writing at 100 pages of 1024 characters.
		BookScanner scanner = new BookScanner(limits());

		for (int page = 0; page < 100; page++) {
			assertTrue(scanner.beginPage(), "page " + page + " rejected");
			assertTrue(scanner.enterNode());
			assertTrue(scanner.addText(1024));
			scanner.exitNode();
		}

		BookScanReport report = scanner.finish();
		assertTrue(report.isSafe(), "a legitimate book was rejected: " + report.verdict());
		assertEquals(100, report.pages());
		assertEquals(102_400, report.characters());
	}

	@Test
	@DisplayName("a modestly nested formatted page passes")
	void nestedFormattingIsSafe() {
		BookScanner scanner = new BookScanner(limits());
		assertTrue(scanner.beginPage());

		for (int depth = 0; depth < 8; depth++) {
			assertTrue(scanner.enterNode(), "legitimate formatting rejected at depth " + depth);
			assertTrue(scanner.addText(64));
		}

		assertTrue(scanner.finish().isSafe());
		assertEquals(8, scanner.finish().maxDepth());
	}

	@Test
	@DisplayName("an empty book is safe")
	void emptyBookIsSafe() {
		assertTrue(new BookScanner(limits()).finish().isSafe());
	}

	// ---------------------------------------------------------------------------------------
	// Size-based bail-out.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("BAIL: page count over the limit stops immediately at the limit")
	void tooManyPagesBailsEarly() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		int accepted = 0;

		// A hostile book claiming a million pages must not cost a million iterations.
		for (int page = 0; page < 1_000_000; page++) {
			if (!scanner.beginPage()) {
				break;
			}

			accepted++;
		}

		BookScanReport report = scanner.finish();
		assertEquals(Verdict.TOO_MANY_PAGES, report.verdict());
		assertEquals(config.maxPages, accepted, "scanning must stop exactly at the page limit");
		assertEquals(config.maxPages, report.pages());
	}

	@Test
	@DisplayName("BAIL: a single oversized page is refused")
	void pageTooLargeIsRefused() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		assertTrue(scanner.beginPage());
		assertTrue(scanner.enterNode());
		assertFalse(scanner.addText(config.maxPageCharacters + 1));
		assertEquals(Verdict.PAGE_TOO_LARGE, scanner.finish().verdict());
	}

	@Test
	@DisplayName("BAIL: a page exactly at the limit is allowed, one over is not")
	void pageLimitBoundaryIsExact() {
		BookSafetyConfig config = limits();

		BookScanner atLimit = new BookScanner(config);
		assertTrue(simplePage(atLimit, config.maxPageCharacters));
		assertTrue(atLimit.finish().isSafe(), "exactly at the limit must be allowed");

		BookScanner overLimit = new BookScanner(config);
		assertFalse(simplePage(overLimit, config.maxPageCharacters + 1));
		assertEquals(Verdict.PAGE_TOO_LARGE, overLimit.finish().verdict());
	}

	@Test
	@DisplayName("BAIL: many individually legal pages still trip the total budget")
	void totalSizeIsEnforcedAcrossPages() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		// Each page is legal on its own; the book as a whole is not. This is the case a
		// per-page-only check would miss entirely.
		boolean stopped = false;

		for (int page = 0; page < config.maxPages; page++) {
			if (!scanner.beginPage() || !scanner.enterNode() || !scanner.addText(config.maxPageCharacters)) {
				stopped = true;
				break;
			}

			scanner.exitNode();
		}

		assertTrue(stopped, "total budget was never enforced");
		assertEquals(Verdict.TOTAL_TOO_LARGE, scanner.finish().verdict());
		assertTrue(scanner.finish().characters() <= config.maxTotalCharacters);
	}

    @Test
	@DisplayName("BAIL: accumulated length cannot overflow into a passing value")
	void hugeLengthsCannotWrapAround() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		assertTrue(scanner.beginPage());
		assertTrue(scanner.enterNode());
		// Two values that would sum past Integer.MAX_VALUE and wrap negative if added as ints.
		assertFalse(scanner.addText(Integer.MAX_VALUE));
		assertFalse(scanner.finish().isSafe());
	}

	// ---------------------------------------------------------------------------------------
	// Nesting-based bail-out. The important property is that it refuses BEFORE recursing.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("BAIL: nesting stops at the depth limit, so a caller never recurses past it")
	void nestingBombStopsAtLimit() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		assertTrue(scanner.beginPage());

		int reached = 0;

		while (scanner.enterNode()) {
			reached++;

			if (reached > config.maxNestingDepth + 10) {
				break; // guard so a broken scanner fails the assert rather than hanging
			}
		}

		assertEquals(config.maxNestingDepth, reached,
				"enterNode must refuse at the limit, bounding the caller's recursion depth");
		assertEquals(Verdict.TOO_DEEP, scanner.finish().verdict());
		assertEquals(config.maxNestingDepth, scanner.finish().maxDepth());
	}

	@Test
	@DisplayName("BAIL: a wide but shallow tree trips the node budget")
	void wideTreeTripsNodeBudget() {
		BookSafetyConfig config = limits();
		config.maxPages = 100_000;
		config.maxTotalCharacters = Integer.MAX_VALUE;
		BookScanner scanner = new BookScanner(config);

		assertTrue(scanner.beginPage());

		int accepted = 0;

		// Flat siblings: depth never grows, so only the node budget can stop this.
		while (scanner.enterNode()) {
			scanner.exitNode();
			accepted++;

			if (accepted > config.maxNodes + 10) {
				break;
			}
		}

		assertEquals(config.maxNodes, accepted);
		assertEquals(Verdict.TOO_MANY_NODES, scanner.finish().verdict());
	}

	@Test
	@DisplayName("exitNode restores depth so siblings do not accumulate depth")
	void siblingsDoNotAccumulateDepth() {
		BookScanner scanner = new BookScanner(limits());
		assertTrue(scanner.beginPage());

		for (int sibling = 0; sibling < 1000; sibling++) {
			assertTrue(scanner.enterNode(), "flat siblings must not exhaust the depth budget");
			scanner.exitNode();
		}

		assertEquals(1, scanner.finish().maxDepth());
		assertTrue(scanner.finish().isSafe());
	}

	// ---------------------------------------------------------------------------------------
	// Once aborted, the scanner stays aborted.
	// ---------------------------------------------------------------------------------------

	@Test
	@DisplayName("a failed scan cannot be resumed by further calls")
	void abortIsSticky() {
		BookSafetyConfig config = limits();
		BookScanner scanner = new BookScanner(config);

		assertTrue(scanner.beginPage());
		assertTrue(scanner.enterNode());
		assertFalse(scanner.addText(config.maxPageCharacters + 1));

		assertTrue(scanner.aborted());
		assertFalse(scanner.beginPage(), "a new page must not clear the failure");
		assertFalse(scanner.enterNode());
		assertFalse(scanner.addText(1));
		assertEquals(Verdict.PAGE_TOO_LARGE, scanner.finish().verdict(),
				"the original reason must survive, not be overwritten by later calls");
	}

	@Test
	@DisplayName("clamp keeps depth in a range that cannot overflow the stack")
	void clampBoundsDepth() {
		BookSafetyConfig config = new BookSafetyConfig();
		config.maxNestingDepth = 100_000;
		config.clamp();
		assertEquals(512, config.maxNestingDepth);

		config.maxNestingDepth = -5;
		config.clamp();
		assertEquals(2, config.maxNestingDepth);
	}
}
