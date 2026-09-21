package com.bankpricemovement;

import java.util.function.Function;

/**
 * A test hook, and nothing else: the one static handle a developer-mode client hands the Effect Lab's HTTP
 * server so a script (or Claude) can drive the 2h Bank Portfolio Tracker sidebar without a person at the keyboard.
 * The same shape as {@code com.lootandbeam.DevBridge}, which the lab's {@code /lb} route reaches; this one is
 * reached by {@code /bpm}.
 *
 * <p><b>It is null in every production client.</b> {@link BankPriceMovementPlugin#startUp()} sets it only
 * when RuneLite's injected {@code @Named("developerMode")} constant is true - which the client only ever
 * binds true for a from-source launch with {@code --developer-mode} and no launcher version
 * (RuneLiteModule.java:120, from RuneLite.java:224) - and {@link BankPriceMovementPlugin#shutDown()} clears
 * it as its first statement. A Plugin Hub build therefore carries a field that is written once with
 * null-equivalent state, read by nothing, and reachable only from a class in the test source set
 * ({@code com.osrslos.lab.LabServer}) that is not in the jar at all.
 *
 * <p>The handler takes ONE command string ({@code key} or {@code key=value}) and answers a JSON string; see
 * {@link BpmCommands} for the command list and {@code docs/effect-lab.md} §3.3 for the {@code /bpm} route
 * that reaches it. It bounces the work onto the Swing thread itself, so it is safe to call from the HTTP
 * thread; it never throws.
 *
 * <p>Volatile because it is written on the EDT (plugin start/stop) and read on the lab's HTTP thread.
 */
public final class BpmDevBridge
{
	/** The running panel's command handler while a developer-mode client has the plugin on; null otherwise. */
	public static volatile Function<String, String> handler;

	private BpmDevBridge()
	{
	}
}
