package com.bankpricemovement;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Standalone runner - boots RuneLite in developer mode with Bank Price Movement injected. This is what
 * {@code gradlew run} starts (see the {@code run} task in build.gradle), and it is the route the Plugin Hub's
 * README and templateplugin/AGENTS.md tell people to test a plugin with.
 *
 * <p>It lives in the TEST source set on purpose: the packaged jar is built from {@code sourceSets.main}
 * only, so this class can never reach a Hub client. It carries no {@code @Test} method - it is a
 * {@code main()}, not a unit test - so the test task simply skips it.
 *
 * <p>Requires assertions: {@code ExternalPluginManager.loadBuiltin} refuses to run without {@code -ea}, which
 * the {@code run} task passes.
 */
public class BankPriceMovementPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BankPriceMovementPlugin.class);
		RuneLite.main(args);
	}
}
