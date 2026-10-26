package com.bankpricemovement;

import com.google.gson.Gson;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A test hook, and nothing else: the one static handle a developer-mode client hands the Effect Lab's HTTP
 * server so a script (or Claude) can drive the 2h Bank Portfolio Tracker sidebar without a person at the keyboard.
 * The same shape as {@code com.lootandbeam.DevBridge}, which the lab's {@code /lb} route reaches; this one is
 * reached by {@code /bpm}.
 *
 * <p><b>It is null in every production client.</b> {@link BankPriceMovementPlugin#startUp()} sets it only
 * when RuneLite's injected {@code @Named("developerMode")} constant is true - which the client only ever
 * binds true for a from-source launch with {@code --developer-mode} and no launcher version
 * (RuneLiteModule.java:120, from RuneLite.java:224) - AND a {@link Factory} has been installed, and
 * {@link BankPriceMovementPlugin#shutDown()} clears the handler as its first statement. The command handler
 * itself is not in this jar: it lives in the test source set, which also holds the one class that installs the
 * factory (the dev launcher calls it before the client starts), so a Plugin Hub build carries two fields that
 * nothing ever fills.
 *
 * <p>The handler takes ONE command string ({@code key} or {@code key=value}) and answers a JSON string; see
 * {@code docs/effect-lab.md} section 3.3 for the command list and the {@code /bpm} route that reaches it. It bounces
 * the work onto the Swing thread itself, so it is safe to call from the HTTP thread; it never throws.
 *
 * <p>Volatile because it is written on the EDT (plugin start/stop) and read on the lab's HTTP thread.
 */
public final class BpmDevBridge
{
	/** The running panel's command handler while a developer-mode client has the plugin on; null otherwise. */
	public static volatile Function<String, String> handler;

	/** What builds the handler for a developer-mode {@code startUp}; null unless the dev launcher installed one. */
	static volatile Factory factory;

	/**
	 * Builds the command handler over the pieces {@code startUp} has just made. The two suppliers read the account
	 * the plugin last saw on the client thread (the hash is 0 when nobody is logged in), and the directory is the
	 * plugin's own, {@code this::getPluginDirectory} - the handler joins whatever folder it writes under it.
	 */
	interface Factory
	{
		Function<String, String> create(BankPriceMovementPanel panel, PriceService service, Gson gson,
			LongSupplier accountHash, Supplier<String> profileType, PriceStore.Directory directory,
			BooleanSupplier showing);
	}

	private BpmDevBridge()
	{
	}
}
