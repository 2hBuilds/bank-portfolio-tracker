package com.bankpricemovement;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.DefaultFocusTraversalPolicy;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.text.ParseException;
import java.time.LocalDate;
import javax.annotation.Nullable;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;

/**
 * The amount box an "Another amount...", "To or from my other account..." or "Change the amount..." press opens (1.2.2), modelled on
 * {@link LegacyDialog}: document-modal to the client window, Enter answers OK, Escape and Cancel answer no, the controls built
 * without a window so a test can press them on a machine with no screen.
 *
 * <p>A bold "Sun 20 Sep", the question {@link #ASK}, the box in the Min / Max boxes' own look prefilled with the
 * amount on offer - and, in grey beside it, "(whole day: -200m)", what the day really changed by. The window is titled
 * {@link #TITLE} (1.2.3). The box takes the focus: it is the
 * only thing to type. The amount is read by {@link #parse}: a minus is gold that left, a plus gold that arrived, no sign the day's
 * own direction; nothing that is not an amount, and not 0, is accepted - the box turns red, the window stays and nothing is saved.
 * While the sidebar hides its amounts (the eye) the box starts empty and the caption is masked - an unsigned amount still takes the
 * day's own direction.
 */
final class TransferDialog
{
	static final String OK_TEXT = "OK";
	static final String ASK = "How much did you send (-) or get (+)?";
	static final String TITLE = "My other account";
	static final String WHOLE_DAY = "(whole day: ";
	private static final int PADDING = 12;

	/** Everything the window shows, built without a window. */
	final JPanel content = new JPanel(new BorderLayout());
	final Widgets.PlaceholderField box = Widgets.gpField("");
	final JButton okButton;
	final JButton cancelButton;
	private final int direction;
	/** The signed gold the player typed, set by OK; null until then, whatever else closes the window. EDT. */
	@Nullable
	Long amount;
	@Nullable
	private JDialog dialog;

	/**
	 * @param initial the amount on offer, signed; 0 for none (the box starts empty)
	 * @param whole   what the day really changed by, signed: the grey caption, and the direction of an amount typed with no sign when
	 *                nothing is on offer
	 * @param hide    whether the sidebar hides its amounts: the box is then left empty and the caption masked
	 */
	TransferDialog(final LocalDate day, final long initial, final long whole, final boolean hide)
	{
		direction = Long.signum(initial != 0L ? initial : whole);
		content.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		content.setBorder(new EmptyBorder(PADDING, PADDING, PADDING, PADDING));

		final JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		entry.setOpaque(false);
		entry.setBorder(new EmptyBorder(6, 0, 0, 0));
		box.setText(hide ? "" : amountText(initial));
		box.addActionListener(e -> ok());
		entry.add(Widgets.fixed(box, 110, box.getPreferredSize().height));
		final JLabel that = Widgets.label(WHOLE_DAY + AmountMask.change(hide, MovementRowPanel.signedGp(whole)) + ")", Widgets.sans(11), ColorScheme.LIGHT_GRAY_COLOR);
		that.setBorder(new EmptyBorder(0, 8, 0, 0));
		entry.add(that);

		final JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		final JLabel heading = Widgets.label(BankHistoryDayRow.weekday(day), Widgets.sansBold(13), Color.WHITE);
		heading.setBorder(new EmptyBorder(0, 0, 4, 0));
		text.add(heading);
		text.add(Widgets.label(ASK, Widgets.sans(12), ColorScheme.LIGHT_GRAY_COLOR));
		text.add(entry);
		for (final Component c : text.getComponents())
		{
			((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		content.add(text, BorderLayout.CENTER);

		okButton = Widgets.smallButton(OK_TEXT, null, e -> ok());
		cancelButton = Widgets.smallButton(LegacyDialog.CANCEL_TEXT, null, e -> close());
		final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		buttons.setOpaque(false);
		buttons.setBorder(new EmptyBorder(PADDING, 0, 0, 0));
		buttons.add(okButton);
		buttons.add(cancelButton);
		content.add(buttons, BorderLayout.SOUTH);

		content.registerKeyboardAction(e -> close(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
	}

	/**
	 * The gold a typed amount names, signed, or null when it names none: a leading minus is gold that left, a plus gold that arrived,
	 * and with neither the amount goes the way of {@code direction} (the day's own; 0 means unknown, which accepts no unsigned
	 * amount). The rest is read by {@link MovementMath#parseGp} - "200m", "1.5m" - and 0 is not an amount, nor is more than
	 * {@link BankHistorySeries.Transfer#MAX_GP} (the client's parser saturates a huge figure at the largest long, which no sum of
	 * notes survives). Whole digits ("12,345,678") are read as the whole number they are: the client's own parser goes through a float
	 * and would round the amount the box itself prefilled from a day's exact change.
	 */
	@Nullable
	static Long parse(@Nullable final String text, final int direction)
	{
		final String typed = text == null ? "" : text.trim();
		final boolean signed = typed.startsWith("-") || typed.startsWith("+");
		final int sign = signed ? (typed.startsWith("-") ? -1 : 1) : direction;
		final String body = signed ? typed.substring(1).trim() : typed;
		try
		{
			final long gp = body.matches("[0-9][0-9,]*") ? Long.parseLong(body.replace(",", "")) : MovementMath.parseGp(body);
			return sign == 0 || gp == 0L || gp > BankHistorySeries.Transfer.MAX_GP ? null : (Long) (sign * gp);
		}
		catch (ParseException | NumberFormatException e)
		{
			return null;
		}
	}

	/** A signed amount as the box shows it: compact when that reads back exactly ("-200m"), else in full ("-12,345,678"); empty for 0. */
	static String amountText(final long gp)
	{
		final long size = Math.abs(gp);
		final String compact = MovementMath.formatGp(size);
		return gp == 0L ? "" : (gp < 0L ? "-" : "+") + (Long.valueOf(size).equals(parse(compact, 1)) ? compact : MovementMath.formatExact(size));
	}

	/**
	 * EDT. Puts the question and answers the signed gold the player typed, or null for Cancel, Escape or a closed window. Blocks until
	 * the dialog is closed. Answers null at once on a machine with no display, which is a test or a server.
	 */
	@Nullable
	static Long ask(@Nullable final Window owner, final LocalDate day, final long initial, final long whole, final boolean hide)
	{
		if (GraphicsEnvironment.isHeadless())
		{
			return null;
		}
		final TransferDialog window = window(owner, day, initial, whole, hide);
		window.dialog.setVisible(true);
		return window.amount;
	}

	/** The window built and sized but not yet shown, OK its default button and the box the first thing to hold the focus. Needs a display. */
	static TransferDialog window(@Nullable final Window owner, final LocalDate day, final long initial, final long whole,
		final boolean hide)
	{
		final TransferDialog window = new TransferDialog(day, initial, whole, hide);
		final JDialog dialog = new JDialog(owner, TITLE);
		dialog.setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
		dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		dialog.setContentPane(window.content);
		dialog.getRootPane().setDefaultButton(window.okButton);
		dialog.setResizable(false);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setFocusTraversalPolicy(new DefaultFocusTraversalPolicy()
		{
			@Override
			public Component getInitialComponent(final Window w)
			{
				return window.box.getTextField();
			}
		});
		window.dialog = dialog;
		return window;
	}

	/** OK, or Enter in the box: takes the amount and closes, or turns the box red and stays. */
	private void ok()
	{
		final Long gp = parse(box.getText(), direction);
		Widgets.markInvalid(box, gp == null);
		if (gp != null)
		{
			amount = gp;
			close();
		}
	}

	private void close()
	{
		final JDialog host = dialog;
		dialog = null;
		if (host != null)
		{
			host.dispose();
		}
	}
}
