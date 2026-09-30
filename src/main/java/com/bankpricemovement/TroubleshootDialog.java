package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.List;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The window the settings menu's <i>Troubleshoot...</i> opens (1.0.8): a plain-words verdict on top, one line under it
 * ({@link #NEXT_TEXT}) that says what to do next and how to send the report, the whole report under that in a
 * read-only monospace box, and two buttons, <i>Copy report</i> and <i>Close</i>. Non-modal, in
 * RuneLite's dark colours, about {@value #WIDTH} x {@value #HEIGHT}.
 *
 * <p>It opens at once on "Checking..." - the checks take a second or two - and {@link #show} fills it in when
 * {@link Troubleshooter} has the answer. Nothing is written to disk: the report lives in this window and, when the
 * player presses <i>Copy report</i>, on the clipboard.
 *
 * <p><b>The model and the window are apart.</b> {@link #contents} is a pure function from the facts and the check
 * results to the verdict and the report, so it needs no display; and the panel of controls ({@link #content}) is
 * built without a window, so a test can press its buttons on a machine with no screen. The {@link JDialog} that
 * hosts the panel is made only by {@link #window}.
 */
public final class TroubleshootDialog
{
	/** The window's title. */
	public static final String TITLE = "2h Bank Portfolio Tracker " + Version.CURRENT + " - Troubleshoot";
	/** The verdict's text until the checks are in. */
	public static final String CHECKING_TEXT = "Checking...";
	/** The one line under every verdict: what to do after trying it, and how to send the report. */
	public static final String NEXT_TEXT = "Then press Troubleshoot again. If it still finds something, press Copy report"
		+ " and paste it in the 2hBuilds Discord or a GitHub issue.";
	public static final String COPY_TEXT = "Copy report";
	/** The copy button's text once it has copied, until the window closes. */
	public static final String COPIED_TEXT = "Copied";
	public static final String CLOSE_TEXT = "Close";

	static final int WIDTH = 560;
	static final int HEIGHT = 440;
	private static final int PADDING = 10;

	/** The verdict and the report for one press. */
	public static final class Contents
	{
		public final String verdict;
		public final String report;

		public Contents(final String verdict, final String report)
		{
			this.verdict = verdict;
			this.report = report;
		}
	}

	/**
	 * The verdict for these facts and results, and the report that carries it first. Leaves one line in the ring of
	 * notes, {@code troubleshoot: <verdict>}, before the report is built - so the report's own list of recent events
	 * ends with it.
	 */
	public static Contents contents(final Diagnostics diagnostics, final Diagnostics.Facts facts,
		final List<CheckResult> checks)
	{
		final String verdict = Troubleshooter.verdict(facts, checks);
		diagnostics.note("troubleshoot: " + verdict);
		return new Contents(verdict, diagnostics.text(facts, verdict, checks));
	}

	/** Everything the window shows, built without a window. */
	final JPanel content = new JPanel(new BorderLayout());
	final JTextArea verdictArea = new JTextArea(CHECKING_TEXT);
	/** {@link #NEXT_TEXT} under the verdict; hidden while the window says "Checking...". */
	final JTextArea nextArea = new JTextArea(NEXT_TEXT);
	final JTextArea reportArea = new JTextArea();
	final JButton copyButton;
	final JButton closeButton;
	private final Consumer<String> clipboard;
	/** The report on show, which the copy button copies; empty until {@link #show}. EDT. */
	private String report = "";
	/** The hosting dialog; null until {@link #window}, and again once closed. EDT. */
	@Nullable
	private JDialog dialog;

	/**
	 * @param clipboard where <i>Copy report</i> puts the text: the panel's clipboard seam, which never throws
	 */
	TroubleshootDialog(final Consumer<String> clipboard)
	{
		this.clipboard = clipboard;
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(PADDING, PADDING, PADDING, PADDING));

		verdictArea.setEditable(false);
		verdictArea.setLineWrap(true);
		verdictArea.setWrapStyleWord(true);
		verdictArea.setOpaque(false);
		verdictArea.setFont(Widgets.sansBold(14));
		verdictArea.setForeground(Color.WHITE);
		verdictArea.setBorder(null);
		// A read-only text box that can take the focus shows its caret; these two are labels that wrap, not boxes.
		verdictArea.setFocusable(false);

		nextArea.setEditable(false);
		nextArea.setLineWrap(true);
		nextArea.setWrapStyleWord(true);
		nextArea.setOpaque(false);
		nextArea.setFont(Widgets.sans(12));
		nextArea.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		nextArea.setBorder(new EmptyBorder(4, 0, 0, 0));
		nextArea.setFocusable(false);
		nextArea.setVisible(false);

		// The verdict, then the line under it, then the padding above the report. Two nested border slots rather than a
		// box layout, which mis-sizes wrapped text.
		final JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(new EmptyBorder(0, 0, PADDING, 0));
		top.add(verdictArea, BorderLayout.NORTH);
		top.add(nextArea, BorderLayout.CENTER);
		content.add(top, BorderLayout.NORTH);

		reportArea.setEditable(false);
		reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
		reportArea.setBackground(ColorScheme.DARK_GRAY_COLOR);
		reportArea.setForeground(Color.WHITE);
		reportArea.setCaretColor(Color.WHITE);
		final JScrollPane scroll = new JScrollPane(reportArea);
		scroll.setBorder(null);
		content.add(scroll, BorderLayout.CENTER);

		copyButton = Widgets.smallButton(COPY_TEXT, null, e -> copy());
		copyButton.setEnabled(false);
		closeButton = Widgets.smallButton(CLOSE_TEXT, null, e -> dispose());
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setOpaque(false);
		buttons.setBorder(new EmptyBorder(PADDING, 0, 0, 0));
		buttons.add(copyButton);
		buttons.add(closeButton);
		content.add(buttons, BorderLayout.SOUTH);
	}

	/**
	 * EDT. Opens the window on "Checking..." and answers it, so the caller can {@link #show} the result later.
	 *
	 * @param owner     the RuneLite frame (the panel's window ancestor), or null
	 * @param clipboard where <i>Copy report</i> puts the text
	 */
	public static TroubleshootDialog open(@Nullable final Window owner, final Consumer<String> clipboard)
	{
		final TroubleshootDialog window = window(owner, clipboard);
		window.dialog.setVisible(true);
		// Whichever control holds the focus is the one Enter presses, and a focused text box shows a caret: so Close
		// while the window says "Checking..." (Copy report is disabled then), and Copy report once the answer is in.
		window.closeButton.requestFocusInWindow();
		return window;
	}

	/**
	 * The window built and sized but not yet shown: a non-modal {@link JDialog} owned by {@code owner}, titled
	 * {@link #TITLE}, closing by disposing, hosting {@link #content}. Needs a display.
	 */
	static TroubleshootDialog window(@Nullable final Window owner, final Consumer<String> clipboard)
	{
		final TroubleshootDialog window = new TroubleshootDialog(clipboard);
		final JDialog dialog = new JDialog(owner, TITLE);
		dialog.setModalityType(Dialog.ModalityType.MODELESS);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setContentPane(window.content);
		dialog.setSize(WIDTH, HEIGHT);
		dialog.setLocationRelativeTo(owner);
		window.dialog = dialog;
		return window;
	}

	/**
	 * EDT. Puts the answer in the window: the verdict in place of "Checking...", {@link #NEXT_TEXT} under it, the
	 * report in the box, scrolled to its top, and the copy button ready.
	 */
	public void show(final Contents contents)
	{
		verdictArea.setText(contents.verdict);
		nextArea.setVisible(true);
		reportArea.setText(contents.report);
		reportArea.setCaretPosition(0);
		report = contents.report;
		copyButton.setEnabled(true);
		// The answer is in: Copy report takes the focus, so Enter copies and no text box shows a caret (the user saw one
		// at the report's first character, 2026-09-30). Answers false without a window, which is a test.
		copyButton.requestFocusInWindow();
	}

	/** EDT. Closes the window; harmless when it is already closed or was never opened. */
	public void dispose()
	{
		final JDialog host = dialog;
		dialog = null;
		if (host != null)
		{
			host.dispose();
		}
	}

	/** The copy button's press: the report to the clipboard, and the button says so until the window closes. */
	private void copy()
	{
		clipboard.accept(report);
		copyButton.setText(COPIED_TEXT);
	}
}
