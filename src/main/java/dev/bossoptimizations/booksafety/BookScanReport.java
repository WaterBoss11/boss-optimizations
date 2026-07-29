package dev.bossoptimizations.booksafety;

/**
 * Outcome of scanning a book. Carries what was measured before the scan stopped, so a rejection
 * can be logged with the number that actually tripped it rather than a vague "too big".
 */
public record BookScanReport(Verdict verdict, int pages, int characters, int maxDepth, int nodes) {
	public enum Verdict {
		SAFE("safe"),
		TOO_MANY_PAGES("too many pages"),
		PAGE_TOO_LARGE("a single page is too large"),
		TOTAL_TOO_LARGE("total content is too large"),
		TOO_DEEP("text is nested too deeply"),
		TOO_MANY_NODES("too many text nodes");

		private final String description;

		Verdict(String description) {
			this.description = description;
		}

		public String description() {
			return description;
		}
	}

	public boolean isSafe() {
		return verdict == Verdict.SAFE;
	}
}
