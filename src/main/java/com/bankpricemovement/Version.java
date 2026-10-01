package com.bankpricemovement;

/**
 * The plugin's version, in one place: the settings menu's last row prints it, the plugin's description ends with it,
 * the start-up line in the client log names it and the diagnostics report opens with it.
 *
 * <p>It is bumped BY HAND each release, in step with {@code version=} in the export's
 * {@code runelite-plugin.properties} (the Hub reads that one). The export's {@code publish.py} refuses to commit when
 * the two differ, so a release cannot go out saying one thing to the Hub and another to the player.
 */
public final class Version
{
	/** The version this build ships as, {@code major.minor.patch}. */
	public static final String CURRENT = "1.0.9";

	private Version()
	{
	}
}
