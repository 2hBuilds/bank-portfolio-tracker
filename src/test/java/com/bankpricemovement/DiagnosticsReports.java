package com.bankpricemovement;

import java.util.Collections;

/**
 * Test helper: the report of a {@link Diagnostics} with no facts, no checks and a stock verdict - what the notes,
 * the errors and the warnings alone come to, for the tests that only want to read those.
 */
final class DiagnosticsReports
{
	private DiagnosticsReports()
	{
	}

	/** The report with nothing filled in by the plugin, the service or the panel. */
	static String report(final Diagnostics diagnostics)
	{
		return diagnostics.text(Diagnostics.Facts.builder().build(), "test verdict", Collections.emptyList());
	}
}
