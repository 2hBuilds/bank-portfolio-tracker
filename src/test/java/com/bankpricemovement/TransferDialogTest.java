package com.bankpricemovement;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.border.CompoundBorder;
import javax.swing.border.LineBorder;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Release 1.2.2 part 2: the amount box ({@link TransferDialog}) - its words, its sign and parse rules, and what OK, Enter, Cancel and
 * Escape do. The controls are built without a window, so the rules run on a machine with no screen; the hosting dialog is checked
 * only where there is one, as {@link LegacyDialogTest} does.
 */
public class TransferDialogTest
{
	private static final LocalDate SUN_20 = LocalDate.of(2026, 9, 20);
	private static final long M = 1_000_000L;

	// ---------------------------------------------------------------------------------------- the reading of what is typed

	@Test
	public void aMinusIsGoldThatLeftAndAPlusGoldThatArrived() throws Exception
	{
		assertEquals(Long.valueOf(-200 * M), TransferDialog.parse("-200m", 1));
		assertEquals(Long.valueOf(200 * M), TransferDialog.parse("+200m", -1));
		assertEquals("spaces round the sign and the amount are only spaces", Long.valueOf(-200 * M), TransferDialog.parse("  - 200m ", 1));
	}

	/**
	 * 1.2.2 verification fix: the client's parser saturates a huge figure at the largest long, and a note that size wraps every change
	 * drawn over it ("-9,22b", "-922b%"). Nothing above 10,000b is an amount; the box turns red and saves nothing.
	 */
	@Test
	public void anAmountBeyondTheCeilingIsNotAnAmount() throws Exception
	{
		assertEquals("the ceiling itself is an amount, in whole digits", Long.valueOf(BankHistorySeries.Transfer.MAX_GP),
			TransferDialog.parse("+10,000,000,000,000", 1));
		assertNotNull("and 10,000b typed as shorthand is one (the client's parser goes through a float)", TransferDialog.parse("-10000b", 1));
		for (final String text : new String[]{"-10001b", "+99999999999b", "9999999999b", "-9223372036854775807", "9223372036854775807",
			"-10,000,000,000,001", "99999999999999999999", "-1000000b"})
		{
			assertNull(text + " is more than any bank holds", TransferDialog.parse(text, -1));
		}
		final TransferDialog dialog = new TransferDialog(SUN_20, 0L, -200 * M, false);
		dialog.box.setText("-99999999999b");
		dialog.okButton.doClick();
		assertNull("nothing is answered", dialog.amount);
	}

	@Test
	public void noSignTakesTheDaysOwnDirection() throws Exception
	{
		assertEquals(Long.valueOf(-200 * M), TransferDialog.parse("200m", -1));
		assertEquals(Long.valueOf(200 * M), TransferDialog.parse("200m", 1));
		assertNull("a day that moved nothing has no direction to take", TransferDialog.parse("200m", 0));
		assertEquals("a sign still decides when there is no direction", Long.valueOf(-200 * M), TransferDialog.parse("-200m", 0));
	}

	@Test
	public void itReadsWhatTheMinMaxBoxesRead() throws Exception
	{
		assertEquals(Long.valueOf(-1_500_000L), TransferDialog.parse("-1.5m", 1));
		assertEquals(Long.valueOf(-12_345_678L), TransferDialog.parse("-12,345,678", 1));
		assertEquals(Long.valueOf(2_000_000_000L), TransferDialog.parse("+2b", 1));
		assertEquals(Long.valueOf(-850L), TransferDialog.parse("-850", 1));
	}

	@Test
	public void zeroAndAnythingThatIsNotAnAmountReadAsNone() throws Exception
	{
		for (final String text : new String[]{"0", "-0", "+0", "0m", "", "   ", "-", "+", "abc", "-abc", "--5m", "+-5m", "-m"})
		{
			assertNull("'" + text + "' is not an amount", TransferDialog.parse(text, -1));
		}
		assertNull(TransferDialog.parse(null, -1));
	}

	/** The box shows an amount compact when that reads back to exactly the same gold, and in full when it would not. */
	@Test
	public void theBoxShowsAnAmountExactly() throws Exception
	{
		assertEquals("-200m", TransferDialog.amountText(-200 * M));
		assertEquals("+3.5m", TransferDialog.amountText(3_500_000L));
		assertEquals("+12,345,678", TransferDialog.amountText(12_345_678L));
		assertEquals("-850", TransferDialog.amountText(-850L));
		assertEquals("", TransferDialog.amountText(0L));
		for (final long gp : new long[]{-200 * M, 3_500_000L, 12_345_678L, -850L, 1_234_567_891L, -2_147_000_001L})
		{
			assertEquals("what the box shows reads back to the gold it was made from", Long.valueOf(gp),
				TransferDialog.parse(TransferDialog.amountText(gp), 1));
		}
	}

	// ---------------------------------------------------------------------------------------- the window's words and controls

	@Test
	public void theBoxCarriesTheMocksWords() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		final List<String> words = new ArrayList<>();
		for (final Component c : all(dialog.content))
		{
			if (c instanceof JLabel)
			{
				words.add(((JLabel) c).getText());
			}
		}
		assertEquals(java.util.Arrays.asList("Sun 20 Sep was a transfer", "Amount moved to or from another account:",
			"(that day: -200m)"), words);
		assertEquals("prefilled with the amount on offer", "-200m", dialog.box.getText());
		assertEquals("OK", dialog.okButton.getText());
		assertEquals("Cancel", dialog.cancelButton.getText());
		assertEquals("Amount moved to or from another account:", TransferDialog.ASK);
	}

	@Test
	public void nothingOnOfferStartsTheBoxEmptyAndTheCaptionNamesTheDay() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, 0L, 4 * M, false);
		assertEquals("", dialog.box.getText());
		boolean caption = false;
		for (final Component c : all(dialog.content))
		{
			caption |= c instanceof JLabel && "(that day: +4m)".equals(((JLabel) c).getText());
		}
		assertTrue(caption);
		dialog.box.setText("3m");
		dialog.okButton.doClick();
		assertEquals("no sign: the day's own direction (it rose)", Long.valueOf(3 * M), dialog.amount);
	}

	/** With the eye shut the box does not say the amount: it starts empty, the caption is masked, and a bare amount still goes the day's way. */
	@Test
	public void withTheEyeShutTheBoxAndTheCaptionSayNoAmount() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, -200 * M, -200 * M, true);
		assertEquals("", dialog.box.getText());
		boolean masked = false;
		for (final Component c : all(dialog.content))
		{
			masked |= c instanceof JLabel && ("(that day: " + AmountMask.change(true, "-200m") + ")").equals(((JLabel) c).getText());
		}
		assertTrue(masked);
		dialog.box.setText("75m");
		dialog.okButton.doClick();
		assertEquals(Long.valueOf(-75 * M), dialog.amount);
	}

	@Test
	public void okTakesTheTypedAmountAndKeepsTheOffersDirection() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		dialog.box.setText("150m");
		dialog.okButton.doClick();
		assertEquals(Long.valueOf(-150 * M), dialog.amount);
		assertFalse("not red", isRed(dialog));
	}

	@Test
	public void enterInTheBoxIsOk() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		dialog.box.setText("+10m");
		dialog.box.getTextField().postActionEvent();
		assertEquals(Long.valueOf(10 * M), dialog.amount);
	}

	@Test
	public void anAmountThatIsNotOneTurnsTheBoxRedAndSavesNothing() throws Exception
	{
		final TransferDialog dialog = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		assertFalse(isRed(dialog));
		for (final String text : new String[]{"", "abc", "0", "-0m"})
		{
			dialog.box.setText(text);
			dialog.okButton.doClick();
			assertNull("'" + text + "' saves nothing", dialog.amount);
			assertTrue("'" + text + "' turns the box red", isRed(dialog));
		}
		dialog.box.setText("5m");
		dialog.okButton.doClick();
		assertEquals("a good amount after a bad one is taken", Long.valueOf(-5 * M), dialog.amount);
		assertFalse("and the box is its own colour again", isRed(dialog));
	}

	@Test
	public void cancelAndEscapeSaveNothing() throws Exception
	{
		final TransferDialog cancelled = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		cancelled.cancelButton.doClick();
		assertNull(cancelled.amount);

		final TransferDialog escaped = new TransferDialog(SUN_20, -200 * M, -200 * M, false);
		final java.awt.event.ActionListener escape = escaped.content
			.getActionForKeyStroke(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0));
		assertNotNull("Escape is bound in the window", escape);
		escape.actionPerformed(null);
		assertNull(escaped.amount);
	}

	@Test
	public void askAnswersNothingOnAMachineWithNoDisplay() throws Exception
	{
		if (!GraphicsEnvironment.isHeadless())
		{
			return;
		}
		assertNull(TransferDialog.ask(null, SUN_20, -200 * M, -200 * M, false));
	}

	@Test
	public void theHostingDialogIsModalTitledAndOkIsItsDefaultWithTheBoxFirst() throws Exception
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final TransferDialog[] made = new TransferDialog[1];
		SwingUtilities.invokeAndWait(() -> made[0] = TransferDialog.window((Window) null, SUN_20, -200 * M, -200 * M, false));
		SwingUtilities.invokeAndWait(() ->
		{
			final JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(made[0].content);
			assertNotNull("the controls are hosted in a dialog", dialog);
			assertEquals("This was a transfer", dialog.getTitle());
			assertEquals(Dialog.ModalityType.DOCUMENT_MODAL, dialog.getModalityType());
			assertEquals(WindowConstants.DISPOSE_ON_CLOSE, dialog.getDefaultCloseOperation());
			assertFalse("built, not shown", dialog.isVisible());
			assertSame("Enter answers OK", made[0].okButton, dialog.getRootPane().getDefaultButton());
			assertSame("the box takes the focus: it is the only thing to type", made[0].box.getTextField(),
				dialog.getFocusTraversalPolicy().getInitialComponent(dialog));
			dialog.dispose();
		});
	}

	private static boolean isRed(final TransferDialog dialog)
	{
		final CompoundBorder border = (CompoundBorder) dialog.box.getBorder();
		return ColorScheme.PROGRESS_ERROR_COLOR.equals(((LineBorder) border.getOutsideBorder()).getLineColor());
	}

	private static List<Component> all(final Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (final Component child : c.getComponents())
		{
			out.add(child);
			if (child instanceof Container)
			{
				out.addAll(all((Container) child));
			}
		}
		return out;
	}
}
