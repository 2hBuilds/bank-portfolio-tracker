package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.DefaultFocusTraversalPolicy;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.annotation.Nullable;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The question the Net Worth History tab asks before it shows the days recorded before 1.0.9 (1.0.9 part 5): the
 * sentence {@link BankPriceMovementPanel#LEGACY_ASK} over two buttons, <i>Include</i> and <i>Cancel</i>, in RuneLite's
 * dark colours. <i>Include</i> is the default button and takes the focus, so Enter answers yes; Escape, <i>Cancel</i>
 * and the window's close box all answer no. It is modal to the client window that owns it: the reader has been asked
 * something about their own numbers, and the sidebar behind it waits for the answer.
 *
 * <p><b>The model and the window are apart.</b> The panel of controls ({@link #content}) is built without a window,
 * so a test can press its buttons on a machine with no screen; the {@link JDialog} that hosts it is made only by
 * {@link #window}.
 */
final class LegacyDialog
{
	static final String INCLUDE_TEXT = "Include";
	static final String CANCEL_TEXT = "Cancel";

	/** The width the question wraps at, in px. */
	static final int TEXT_WIDTH = 320;
	private static final int PADDING = 12;
	/** The key Escape is bound under in {@link #content}'s action map. */
	static final String CANCEL_KEY = "cancel";

	/** Everything the window shows, built without a window. */
	final JPanel content = new JPanel(new BorderLayout());
	final JTextArea textArea;
	final JButton includeButton;
	final JButton cancelButton;
	/** True once <i>Include</i> was pressed; false until then, whatever else closes the window. EDT. */
	boolean included;
	/** The hosting dialog; null until {@link #window}, and again once closed. EDT. */
	@Nullable
	private JDialog dialog;

	LegacyDialog(final String question)
	{
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(PADDING, PADDING, PADDING, PADDING));

		textArea = new JTextArea(question);
		textArea.setEditable(false);
		textArea.setLineWrap(true);
		textArea.setWrapStyleWord(true);
		textArea.setOpaque(false);
		textArea.setFont(Widgets.sans(12));
		textArea.setForeground(Color.WHITE);
		textArea.setBorder(null);
		// A read-only box that can take the focus shows its caret; this one is a label that wraps, and the first
		// thing that may hold the focus must be Include.
		textArea.setFocusable(false);
		// A wrapped text area has no height of its own until it knows its width: give it the width, ask, and pin both.
		textArea.setSize(new Dimension(TEXT_WIDTH, Short.MAX_VALUE));
		textArea.setPreferredSize(new Dimension(TEXT_WIDTH, textArea.getPreferredSize().height));
		content.add(textArea, BorderLayout.CENTER);

		includeButton = Widgets.smallButton(INCLUDE_TEXT, null, e -> answer(true));
		cancelButton = Widgets.smallButton(CANCEL_TEXT, null, e -> answer(false));
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setOpaque(false);
		buttons.setBorder(new EmptyBorder(PADDING, 0, 0, 0));
		buttons.add(includeButton);
		buttons.add(cancelButton);
		content.add(buttons, BorderLayout.SOUTH);

		// Escape is Cancel wherever the focus is in the window.
		content.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CANCEL_KEY);
		content.getActionMap().put(CANCEL_KEY, new AbstractAction()
		{
			@Override
			public void actionPerformed(final ActionEvent e)
			{
				answer(false);
			}
		});
	}

	/**
	 * EDT. Puts the question to the reader and answers what they chose: true for <i>Include</i> only. Blocks - the dialog
	 * is modal - until it is closed. Answers false at once on a machine with no display, which is a test or a server.
	 *
	 * @param owner the RuneLite frame (the panel's window ancestor), or null
	 */
	static boolean ask(@Nullable final Window owner, final String question)
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return false;
		}
		final LegacyDialog window = window(owner, question);
		window.dialog.setVisible(true);
		return window.included;
	}

	/**
	 * The window built and sized but not yet shown: a {@link JDialog} owned by {@code owner}, titled with the check
	 * box's own words, modal to its owner's windows, closing by disposing, with <i>Include</i> as its default button and
	 * the focus on it when the window first gets it. Needs a display.
	 */
	static LegacyDialog window(@Nullable final Window owner, final String question)
	{
		final LegacyDialog window = new LegacyDialog(question);
		final JDialog dialog = new JDialog(owner, BankPriceMovementPanel.LEGACY_TEXT);
		dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setContentPane(window.content);
		dialog.getRootPane().setDefaultButton(window.includeButton);
		dialog.setResizable(false);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		// Where the focus lands when the window first gets it: Include, so Enter answers yes and no text box shows a caret.
		dialog.setFocusTraversalPolicy(new DefaultFocusTraversalPolicy()
		{
			@Override
			public Component getInitialComponent(final Window w)
			{
				return window.includeButton;
			}
		});
		window.dialog = dialog;
		return window;
	}

	/** A button's press, or Escape: remembers the answer and closes the window; harmless with no window. */
	private void answer(final boolean yes)
	{
		included = yes;
		final JDialog host = dialog;
		dialog = null;
		if (host != null)
		{
			host.dispose();
		}
	}
}
