package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/**
 * {@link TroubleshootDialog}: the model ({@code contents}) needs no display and is pinned whole, including one full
 * report printed to the test log; the controls are built without a window and pressed here; the {@link JDialog}
 * itself is built (not shown) only where a display exists.
 */
public class TroubleshootDialogTest
{
	/** 2026-09-30T14:05:09Z. */
	private static final long T0 = 1_790_777_109_000L;

	private final AtomicLong clock = new AtomicLong(T0);
	private final Diagnostics diagnostics = new Diagnostics(clock::get, ZoneId.of("America/Toronto"));

	// ---- the model

	@Test
	public void theContentsAreTheVerdictAndTheReportThatCarriesItFirst()
	{
		final Diagnostics.Facts facts = Diagnostics.Facts.builder().loggedIn(false).build();

		final TroubleshootDialog.Contents contents = TroubleshootDialog.contents(diagnostics, facts, Collections.emptyList());

		assertEquals(Troubleshooter.VERDICT_NOT_LOGGED_IN, contents.verdict);
		final String[] lines = contents.report.split("\n");
		assertEquals("2h Bank Portfolio Tracker " + Version.CURRENT + " - diagnostics", lines[0]);
		assertEquals("Verdict: " + contents.verdict, lines[1]);
	}

	@Test
	public void buildingTheContentsLeavesOneNoteWithTheVerdictInTheRing()
	{
		final Diagnostics.Facts facts = Diagnostics.Facts.builder().loggedIn(false).build();

		final TroubleshootDialog.Contents contents = TroubleshootDialog.contents(diagnostics, facts, Collections.emptyList());

		final String note = "troubleshoot: " + Troubleshooter.VERDICT_NOT_LOGGED_IN + "\n";
		assertEquals("exactly one, and in the report it produced: " + contents.report, 1, count(contents.report, note));
		assertTrue(contents.report.endsWith(note));
	}

	/**
	 * The report of a full run, printed once to the test log so a reader can see what a support person would get - and
	 * pinned: sections in order, the verdict first, no hash, no path, no item.
	 */
	@Test
	public void aFullReportReadsAsASupportPersonWouldWantIt()
	{
		diagnostics.note("plugin started, version " + Version.CURRENT);
		diagnostics.note("game state: logged in");
		diagnostics.note("bank interface opened");
		diagnostics.note("bank event: read (412 stacks, 28 carried)");
		diagnostics.note("publish: loggedIn=true, bankLoaded=true, rows=412");
		diagnostics.fetchLog().ok(FetchLog.MAPPING, 200, 131L, 84_213L, 4_566);
		diagnostics.fetchLog().ok(FetchLog.PRICE_INDEX, 200, 52L, 3_611L, 250);
		diagnostics.fetchLog().ok(FetchLog.GUIDE_TABLES, 200, 374L, 126_770L, 3);
		diagnostics.fetchLog().failed(FetchLog.LIVE_LATEST, 503, 88L, 0L, "HTTP 503");
		diagnostics.fetchLog().serverDate("Wed, 30 Sep 2026 14:05:07 GMT");
		diagnostics.error("a listener threw", new IllegalStateException("planted for the sample"));
		diagnostics.warnOnce("listener-threw java.lang.IllegalStateException");
		final long task = diagnostics.watchdog().started("pricing the bank", false);
		clock.set(T0 + 1_500L);
		diagnostics.watchdog().finished(task);
		final Diagnostics.Facts.Builder b = Diagnostics.Facts.builder();
		b.loggedIn(true).accountKnown(true);
		b.line(Diagnostics.PLAYER, "profile", "STANDARD");
		b.line(Diagnostics.PLAYER, "world types", "MEMBERS");
		b.line(Diagnostics.PLAYER, "bank window open now", "no");
		b.bankEvents(9, 2, 3, 0);
		b.line(Diagnostics.BANK, "last read at", "14:03:41");
		b.line(Diagnostics.BANK, "stacks read", "bank 412, inventory 24, worn 4");
		b.line(Diagnostics.BANK, "bank loaded from disk at login", "yes");
		b.line(Diagnostics.BANK, "bank loaded (as the status says)", "yes");
		b.line(Diagnostics.PRICING, "rows by source", "live 61, guide 330, parts 3, alch 18, unpriced 0 (of 412 listed)");
		b.line(Diagnostics.PRICING, "anchor day", "2026-09-30");
		b.line(Diagnostics.PRICING, "degraded", "-");
		b.line(Diagnostics.PRICING, "agreement with RuneLite's price table", "98 of 100 samples match");
		b.line(Diagnostics.PRICING, "1d compares against", "guide 2026-09-29, live 2026-09-29");
		b.line(Diagnostics.PRICING, "prices at", "14:00");
		b.line(Diagnostics.HISTORY, "state", "LOADED, 40 readings");
		b.line(Diagnostics.HISTORY, "first day", "2026-08-18");
		b.line(Diagnostics.HISTORY, "last day", "2026-09-30");
		b.line(Diagnostics.HISTORY, "today's reading recorded", "yes");
		b.line(Diagnostics.HISTORY, "last write", "ok");
		b.line(Diagnostics.SIDEBAR, "tab", "ITEMS");
		b.card("LIST");
		b.line(Diagnostics.SIDEBAR, "window", "D1");
		b.line(Diagnostics.SIDEBAR, "rows", "412");
		b.line(Diagnostics.SETTINGS, "gpMin", "0");
		b.line(Diagnostics.SETTINGS, "livePrices", "true");
		b.lastErrorWhat(diagnostics.lastErrorWhat());
		final List<CheckResult> checks = Arrays.asList(
			new CheckResult("plugin version", true, 200, 210L, "up to date (" + Version.CURRENT + ")"),
			new CheckResult("wiki mapping", true, 200, 121L, "reachable"),
			new CheckResult("wiki price index", true, 200, 160L, "reachable"),
			new CheckResult("live prices", true, 200, 97L, "reachable"),
			new CheckResult("data folder", true, 0, 4L, "wrote one byte and removed it"));

		final TroubleshootDialog.Contents contents = TroubleshootDialog.contents(diagnostics, b.build(), checks);

		System.out.println("---- sample report ----\n" + contents.report + "---- end of sample report ----");
		assertEquals(Troubleshooter.VERDICT_FINE, contents.verdict);
		assertTrue(contents.report.startsWith("2h Bank Portfolio Tracker " + Version.CURRENT
			+ " - diagnostics\nVerdict: Everything looks fine here."));
		final List<String> order = Arrays.asList("\nBuild\n", "\nPlayer\n", "\nBank\n", "\nPrices\n", "\nPricing\n",
			"\nHistory\n", "\nSidebar\n", "\nBackground work\n", "\nSettings\n", "\nErrors\n", "\nChecks\n",
			"\nRecent events, oldest first\n");
		int from = 0;
		for (final String heading : order)
		{
			final int at = contents.report.indexOf(heading, from);
			assertTrue(heading.trim() + " in order", at >= from && at >= 0);
			from = at + 1;
		}
		assertFalse("no account hash", Pattern.compile("\\d{15,}").matcher(contents.report).find());
		assertFalse("no path", contents.report.contains("C:\\") || contents.report.contains(".runelite"));
		assertTrue(contents.report.contains("clock skew: +2 s"));
		assertTrue(contents.report.contains("world types: MEMBERS"));
		assertTrue(contents.report.contains("live latest: last attempt 10:05:09, failed, HTTP 503"));
	}

	// ---- the controls, without a window

	@Test
	public void itOpensOnCheckingWithTheCopyButtonWaiting()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		assertEquals("Checking...", window.verdictArea.getText());
		assertFalse("the second line waits for the answer", window.nextArea.isVisible());
		assertEquals("", window.reportArea.getText());
		assertFalse("nothing to copy yet", window.copyButton.isEnabled());
		assertEquals("Copy report", window.copyButton.getText());
		assertEquals("Close", window.closeButton.getText());
	}

	@Test
	public void showPutsTheVerdictAndTheReportInPlaceAndReadiesTheCopyButton()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		window.show(new TroubleshootDialog.Contents("Everything looks fine.", "line one\nline two\n"));

		assertEquals("Everything looks fine.", window.verdictArea.getText());
		assertTrue("the second line is shown with the answer", window.nextArea.isVisible());
		assertEquals(TroubleshootDialog.NEXT_TEXT, window.nextArea.getText());
		assertEquals("line one\nline two\n", window.reportArea.getText());
		assertEquals("scrolled to the top", 0, window.reportArea.getCaretPosition());
		assertTrue(window.copyButton.isEnabled());
	}

	@Test
	public void theSecondLineIsTheSameForEveryVerdictAndIsWordForWordTheContracts()
	{
		assertEquals("Then press Troubleshoot again. If it still finds something, press Copy report and paste it in the "
			+ "2hBuilds Discord or a GitHub issue.", TroubleshootDialog.NEXT_TEXT);
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		window.show(new TroubleshootDialog.Contents(Troubleshooter.VERDICT_NOT_LOGGED_IN, "report one\n"));
		assertEquals(TroubleshootDialog.NEXT_TEXT, window.nextArea.getText());
		window.show(new TroubleshootDialog.Contents(Troubleshooter.VERDICT_FINE, "report two\n"));
		assertEquals(TroubleshootDialog.NEXT_TEXT, window.nextArea.getText());
		assertEquals("the verdict area still reads the verdict alone", Troubleshooter.VERDICT_FINE,
			window.verdictArea.getText());
		assertFalse("no link, no button, no address", window.nextArea.getText().contains("http")
			|| window.nextArea.getText().contains("discord.gg"));
	}

	@Test
	public void theSecondLineIsPlainLightGreyAndWrappedAndNeverInTheReport()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		assertFalse(window.nextArea.isEditable());
		assertFalse("a focused read-only box shows a caret; the user saw one after the verdict (2026-09-30)",
			window.verdictArea.isFocusable());
		assertFalse(window.nextArea.isFocusable());
		assertTrue(window.nextArea.getLineWrap());
		assertTrue(window.nextArea.getWrapStyleWord());
		assertFalse("plain, not bold", window.nextArea.getFont().isBold());
		assertEquals(12, window.nextArea.getFont().getSize());
		assertEquals(ColorScheme.LIGHT_GRAY_COLOR, window.nextArea.getForeground());

		final TroubleshootDialog.Contents contents = TroubleshootDialog.contents(diagnostics,
			Diagnostics.Facts.builder().loggedIn(false).build(), Collections.emptyList());
		assertFalse("the report carries the verdict alone", contents.report.contains(TroubleshootDialog.NEXT_TEXT));
		assertFalse(contents.report.contains("Then press Troubleshoot again"));
	}

	@Test
	public void copyReportPutsTheReportOnTheClipboardAndSaysCopied()
	{
		final List<String> copied = new ArrayList<>();
		final TroubleshootDialog window = new TroubleshootDialog(copied::add);
		window.show(new TroubleshootDialog.Contents("v", "the whole report\n"));

		window.copyButton.doClick();

		assertEquals(Arrays.asList("the whole report\n"), copied);
		assertEquals("Copied", window.copyButton.getText());
		window.copyButton.doClick();
		assertEquals("a second press copies again", 2, copied.size());
	}

	@Test
	public void theReportBoxIsReadOnlyMonospaceAndTheVerdictIsBoldAndWrapped()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		assertFalse(window.reportArea.isEditable());
		assertEquals(java.awt.Font.MONOSPACED, window.reportArea.getFont().getName());
		assertEquals(11, window.reportArea.getFont().getSize());
		assertFalse(window.verdictArea.isEditable());
		assertTrue(window.verdictArea.getLineWrap());
		assertTrue(window.verdictArea.getWrapStyleWord());
		assertTrue(window.verdictArea.getFont().isBold());
		assertEquals(Color.WHITE, window.verdictArea.getForeground());
		assertEquals(ColorScheme.DARKER_GRAY_COLOR, window.content.getBackground());
	}

	@Test
	public void theLayoutIsVerdictAndItsSecondLineOnTopTheReportInAScrollPaneAndTwoButtonsAtTheBottom()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });
		final BorderLayout layout = (BorderLayout) window.content.getLayout();

		final Container top = (Container) layout.getLayoutComponent(BorderLayout.NORTH);
		final BorderLayout topLayout = (BorderLayout) top.getLayout();
		assertSame("the verdict first", window.verdictArea, topLayout.getLayoutComponent(BorderLayout.NORTH));
		assertSame("then the line under it", window.nextArea, topLayout.getLayoutComponent(BorderLayout.CENTER));
		final Component centre = layout.getLayoutComponent(BorderLayout.CENTER);
		assertTrue(centre instanceof JScrollPane);
		assertSame(window.reportArea, ((JScrollPane) centre).getViewport().getView());
		final Container south = (Container) layout.getLayoutComponent(BorderLayout.SOUTH);
		assertEquals(Arrays.asList(window.copyButton, window.closeButton), Arrays.asList(south.getComponents()));
	}

	@Test
	public void closeWithNoWindowIsHarmless()
	{
		final TroubleshootDialog window = new TroubleshootDialog(text -> { });

		window.closeButton.doClick();
		window.dispose();
	}

	@Test
	public void theTitleNamesThePluginItsVersionAndThePurpose()
	{
		assertEquals("2h Bank Portfolio Tracker " + Version.CURRENT + " - Troubleshoot", TroubleshootDialog.TITLE);
		assertEquals(560, TroubleshootDialog.WIDTH);
		assertEquals(440, TroubleshootDialog.HEIGHT);
	}

	// ---- the window itself, where there is a display

	@Test
	public void theHostingDialogIsNonModalDisposesOnCloseAndClosesWhenTheCloseButtonIsPressed() throws Exception
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final TroubleshootDialog[] made = new TroubleshootDialog[1];
		SwingUtilities.invokeAndWait(() -> made[0] = TroubleshootDialog.window((Window) null, text -> { }));
		final TroubleshootDialog window = made[0];
		assertNotNull(window);

		SwingUtilities.invokeAndWait(() ->
		{
			final JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(window.content);
			assertNotNull("the controls are hosted in a dialog", dialog);
			assertEquals(TroubleshootDialog.TITLE, dialog.getTitle());
			assertEquals(java.awt.Dialog.ModalityType.MODELESS, dialog.getModalityType());
			assertEquals(javax.swing.WindowConstants.DISPOSE_ON_CLOSE, dialog.getDefaultCloseOperation());
			assertEquals(TroubleshootDialog.WIDTH, dialog.getWidth());
			assertEquals(TroubleshootDialog.HEIGHT, dialog.getHeight());
			assertFalse("built, not shown", dialog.isVisible());
			window.closeButton.doClick();
			assertFalse("pressing Close disposed it", dialog.isDisplayable());
		});
	}

	private static int count(final String text, final String needle)
	{
		int count = 0;
		for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length()))
		{
			count++;
		}
		return count;
	}
}
