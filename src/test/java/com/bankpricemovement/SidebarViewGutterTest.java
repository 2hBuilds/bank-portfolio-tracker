package com.bankpricemovement;

import java.awt.Component;
import java.awt.Dimension;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.find;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.rows;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Amendment 9.8: the pinned header reserves the gutter of the SHOWING card's scroll bar - the History card has a
 * scroll pane of its own - so the header's cards end on the same pixel as whichever list is under them, and the
 * gutter follows the toggle both ways.
 *
 * <p>The History column is made tall by giving it a preferred height from here, so the proof does not depend on what
 * the History view itself draws.
 */
public class SidebarViewGutterTest
{
	private static final int OUTER = PluginPanel.PANEL_WIDTH + PluginPanel.SCROLLBAR_WIDTH;

	private PriceService service;
	private BankPriceMovementPanel panel;
	private PriceService.Listener listener;

	@Before
	public void setUp() throws Exception
	{
		service = mock(PriceService.class);
		final SidebarViewPanelTest.Prefs prefs = new SidebarViewPanelTest.Prefs();
		onEdt(() ->
		{
			panel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			panel.setClock(() -> NOW);
		});
		final ArgumentCaptor<PriceService.Listener> captor = ArgumentCaptor.forClass(PriceService.Listener.class);
		verify(service).addListener(captor.capture());
		listener = captor.getValue();
	}

	@After
	public void tearDown() throws Exception
	{
		onEdt(() -> panel.stop());
	}

	@Test
	public void theHeaderReservesTheGutterOfTheListThatIsShowing() throws Exception
	{
		onEdt(() -> listener.onRows(rows(3), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 60_000L)));
		onEdt(() ->
		{
			layout();
			assertFalse("three rows do not scroll", panel.scrollPane().getVerticalScrollBar().isVisible());
			assertEquals(0, panel.gutter());

			historyColumn().setPreferredSize(new Dimension(Widgets.CONTENT_WIDTH, 3_000));
			panel.pressView(SidebarView.HISTORY);
			layout();
			final JScrollBar bar = historyScroll().getVerticalScrollBar();
			assertTrue("the fixture has to actually scroll in History", bar.isVisible());
			assertTrue(bar.getWidth() > 0);
			assertEquals("the header reserves the History list's bar", bar.getWidth(), panel.gutter());

			panel.pressView(SidebarView.ITEMS);
			layout();
			assertEquals("...and hands it back over the short item list", 0, panel.gutter());
		});

		onEdt(() -> listener.onRows(rows(40), status(BankHistorySeries.EMPTY, VALUE_NOW, NOW - 30_000L)));
		onEdt(() ->
		{
			historyColumn().setPreferredSize(null);
			layout();
			final JScrollBar bar = panel.scrollPane().getVerticalScrollBar();
			assertTrue("forty rows scroll", bar.isVisible());
			assertEquals(bar.getWidth(), panel.gutter());

			panel.pressView(SidebarView.HISTORY);
			layout();
			assertFalse("the short History card does not scroll", historyScroll().getVerticalScrollBar().isVisible());
			assertEquals("the hidden item list's bar is not the header's business", 0, panel.gutter());
		});
	}

	private void layout()
	{
		panel.setSize(OUTER, 900);
		LookRenderer.layoutTree(panel);
		LookRenderer.layoutTree(panel);
	}

	/** The column the History view is mounted in (amendment 9.8). */
	private JComponent historyColumn()
	{
		final BankHistoryView view = find(panel, BankHistoryView.class);
		assertNotNull("the History view is mounted", view);
		return (JComponent) view.getParent();
	}

	private JScrollPane historyScroll()
	{
		final Component pane = SwingUtilities.getAncestorOfClass(JScrollPane.class, find(panel, BankHistoryView.class));
		assertNotNull(pane);
		return (JScrollPane) pane;
	}
}
