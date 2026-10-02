package com.bankpricemovement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

/**
 * {@link LegacyDialog} (1.0.9 part 5), the question the History tab asks before it shows the days recorded before
 * 1.0.9: the controls are built without a window and pressed here; the {@link JDialog} that hosts them is built (not
 * shown) only where a display exists.
 */
public class LegacyDialogTest
{
	@Test
	public void theWordsAreTheQuestionAndTwoButtonsInOrder()
	{
		final LegacyDialog window = new LegacyDialog(BankPriceMovementPanel.LEGACY_ASK);

		assertEquals("Days before v1.0.9 did not count open G.E. orders, so their net worth totals may read low."
			+ " Include them anyway?", window.textArea.getText());
		assertEquals("Include", LegacyDialog.INCLUDE_TEXT);
		assertEquals("Cancel", LegacyDialog.CANCEL_TEXT);
		assertEquals("Include", window.includeButton.getText());
		assertEquals("Cancel", window.cancelButton.getText());
		final BorderLayout layout = (BorderLayout) window.content.getLayout();
		assertSame(window.textArea, layout.getLayoutComponent(BorderLayout.CENTER));
		final Container south = (Container) layout.getLayoutComponent(BorderLayout.SOUTH);
		assertEquals("Include first, Cancel after it", Arrays.asList(window.includeButton, window.cancelButton),
			Arrays.asList(south.getComponents()));
		assertFalse("not answered until something is pressed", window.included);
	}

	@Test
	public void theLookIsTheClients()
	{
		final LegacyDialog window = new LegacyDialog("?");

		assertEquals(ColorScheme.DARKER_GRAY_COLOR, window.content.getBackground());
		assertFalse("a label that wraps, not a box with a caret", window.textArea.isFocusable());
		assertFalse(window.textArea.isEditable());
		assertTrue(window.textArea.getLineWrap());
		assertEquals(LegacyDialog.TEXT_WIDTH, window.textArea.getPreferredSize().width);
		assertTrue("tall enough for the wrapped sentence", window.textArea.getPreferredSize().height > 0);
		assertTrue(window.includeButton.isFocusable());
	}

	@Test
	public void includeAnswersYes()
	{
		final LegacyDialog window = new LegacyDialog("?");

		window.includeButton.doClick();

		assertTrue(window.included);
	}

	@Test
	public void cancelAnswersNo()
	{
		final LegacyDialog window = new LegacyDialog("?");

		window.cancelButton.doClick();

		assertFalse(window.included);
	}

	/** Escape is Cancel wherever the focus is in the window: bound on the content for the whole window. */
	@Test
	public void escapeIsCancel()
	{
		final LegacyDialog window = new LegacyDialog("?");
		final KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

		final Object key = window.content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);

		assertEquals(LegacyDialog.CANCEL_KEY, key);
		final Action cancel = window.content.getActionMap().get(key);
		assertNotNull(cancel);
		cancel.actionPerformed(new ActionEvent(window.content, ActionEvent.ACTION_PERFORMED, "cancel"));
		assertFalse(window.included);
	}

	/** With no window to close, a press closes nothing and is harmless; it only sets the answer. */
	@Test
	public void aWindowThatWasNeverOpenedIsHarmlessToAnswer()
	{
		final LegacyDialog window = new LegacyDialog("?");

		window.cancelButton.doClick();
		window.includeButton.doClick();

		assertTrue("harmless with no window, the last press stands", window.included);
	}

	@Test
	public void askAnswersNoOnAMachineWithNoDisplay()
	{
		if (!GraphicsEnvironment.isHeadless())
		{
			return;
		}
		assertFalse(LegacyDialog.ask(null, BankPriceMovementPanel.LEGACY_ASK));
	}

	// ---- the window itself, where there is a display

	@Test
	public void theHostingDialogIsModalTitledDisposingAndIncludeIsItsDefaultAndFirstFocus() throws Exception
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		final LegacyDialog[] made = new LegacyDialog[1];
		SwingUtilities.invokeAndWait(() -> made[0] = LegacyDialog.window((Window) null, BankPriceMovementPanel.LEGACY_ASK));
		final LegacyDialog window = made[0];
		assertNotNull(window);

		SwingUtilities.invokeAndWait(() ->
		{
			final JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(window.content);
			assertNotNull("the controls are hosted in a dialog", dialog);
			assertEquals(BankPriceMovementPanel.LEGACY_TEXT, dialog.getTitle());
			assertEquals(Dialog.ModalityType.DOCUMENT_MODAL, dialog.getModalityType());
			assertEquals(WindowConstants.DISPOSE_ON_CLOSE, dialog.getDefaultCloseOperation());
			assertFalse("built, not shown", dialog.isVisible());
			assertSame("Enter answers Include", window.includeButton, dialog.getRootPane().getDefaultButton());
			final Component first = dialog.getFocusTraversalPolicy().getInitialComponent(dialog);
			assertSame("Include takes the focus as the window opens", window.includeButton, first);
			assertTrue(dialog.getWidth() >= LegacyDialog.TEXT_WIDTH);
		});
	}

	@Test
	public void includeClosesTheWindowWithYesAndCancelAndEscapeCloseItWithNo() throws Exception
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return;
		}
		for (final String how : new String[]{"include", "cancel", "escape"})
		{
			final LegacyDialog[] made = new LegacyDialog[1];
			SwingUtilities.invokeAndWait(() -> made[0] = LegacyDialog.window((Window) null, "?"));
			final LegacyDialog window = made[0];
			SwingUtilities.invokeAndWait(() ->
			{
				final JDialog dialog = (JDialog) SwingUtilities.getWindowAncestor(window.content);
				assertNotNull(dialog);
				if (how.equals("include"))
				{
					window.includeButton.doClick();
				}
				else if (how.equals("cancel"))
				{
					window.cancelButton.doClick();
				}
				else
				{
					window.content.getActionMap().get(LegacyDialog.CANCEL_KEY)
						.actionPerformed(new ActionEvent(window.content, ActionEvent.ACTION_PERFORMED, "cancel"));
				}
				assertFalse(how + " closed the window", dialog.isDisplayable());
				assertEquals(how, how.equals("include"), window.included);
			});
		}
	}
}
