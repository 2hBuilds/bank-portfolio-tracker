package com.bankpricemovement;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import net.runelite.client.game.ItemManager;
import org.junit.After;
import org.junit.Test;
import static com.bankpricemovement.SidebarViewPanelTest.NOW;
import static com.bankpricemovement.SidebarViewPanelTest.TODAY;
import static com.bankpricemovement.SidebarViewPanelTest.VALUE_NOW;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.rows;
import static com.bankpricemovement.SidebarViewPanelTest.series;
import static com.bankpricemovement.SidebarViewPanelTest.status;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Addendum AU on the dev bridge (amendment 9.11): {@code view=items|history} presses the toggle's own road,
 * {@code range=7d|30d|90d|all} moves the History chart alone, both refuse a word they do not know in the words 9.11
 * gives, and every answer carries {@code view} and {@code bankHistory} at the TOP level.
 */
public class BpmViewVerbsTest
{
	private final Gson gson = new Gson();
	private BankPriceMovementPanel realPanel;

	@After
	public void tearDown() throws Exception
	{
		if (realPanel != null)
		{
			onEdt(() -> realPanel.stop());
		}
	}

	private static PriceService service()
	{
		final PriceService service = mock(PriceService.class);
		when(service.filter()).thenReturn(RowFilter.DEFAULT);
		when(service.currentRows()).thenReturn(Collections.emptyList());
		return service;
	}

	private JsonObject send(BpmCommands bridge, String cmd)
	{
		final String s = bridge.apply(cmd);
		assertNotNull("the bridge always answers", s);
		return gson.fromJson(s, JsonObject.class);
	}

	@Test
	public void theTwoVerbsPressThePanelsRoadsAndRefuseWhatTheyDoNotKnow()
	{
		final BankPriceMovementPanel panel = mock(BankPriceMovementPanel.class);
		final BpmCommands dev = new BpmCommands(panel, service(), gson);

		assertTrue(send(dev, "view=history").get("ok").getAsBoolean());
		verify(panel).pressView(SidebarView.HISTORY);
		assertTrue(send(dev, "view= Items ").get("ok").getAsBoolean());
		verify(panel).pressView(SidebarView.ITEMS);

		final JsonObject badView = send(dev, "view=Chart");
		assertFalse(badView.get("ok").getAsBoolean());
		assertEquals("view= wants items or history, not 'Chart'", badView.get("error").getAsString());
		assertEquals("view= wants items or history, not ''", send(dev, "view").get("error").getAsString());

		assertTrue(send(dev, "range=30D").get("ok").getAsBoolean());
		verify(panel).setHistoryRange(BankHistoryRange.D30);
		send(dev, "range=all");
		verify(panel).setHistoryRange(BankHistoryRange.ALL);
		send(dev, "range=7d");
		verify(panel).setHistoryRange(BankHistoryRange.D7);
		send(dev, "range=90d");
		verify(panel).setHistoryRange(BankHistoryRange.D90);
		final JsonObject badRange = send(dev, "range=1d");
		assertFalse(badRange.get("ok").getAsBoolean());
		assertEquals("range= wants 7d, 30d, 90d or all, not '1d'", badRange.get("error").getAsString());
		assertEquals("range= wants 7d, 30d, 90d or all, not ''", send(dev, "range=").get("error").getAsString());

		// The refusal of an unknown command names both new verbs.
		final String unknown = send(dev, "nope").get("error").getAsString();
		assertTrue(unknown, unknown.contains("view=") && unknown.contains("range="));

		// A mocked panel answers null for the view (Gson leaves it out) and an empty map for the History view.
		final JsonObject state = send(dev, "state");
		assertFalse(state.has("view"));
		assertTrue(state.get("bankHistory").isJsonObject());
		assertEquals(0, state.getAsJsonObject("bankHistory").size());
		verify(panel, never()).setView(any());
	}

	/** Against the real panel: the echoes, the write the toggle's road makes, and the pager going to History. */
	@Test
	public void theEchoesAreTopLevelAndFollowTheRealPanel() throws Exception
	{
		final PriceService service = service();
		final BankPriceMovementPanel.Prefs prefs = new BankPriceMovementPanel.Prefs()
		{
			@Override
			public RowFilter load()
			{
				return null;
			}

			@Override
			public void save(RowFilter filter)
			{
			}
		};
		final PriceService.Status status = status(series(TODAY.minusDays(1), TODAY), VALUE_NOW, NOW - 60_000L);
		when(service.currentStatus()).thenReturn(status);
		when(service.currentRows()).thenReturn(rows(3));
		onEdt(() ->
		{
			realPanel = new BankPriceMovementPanel(mock(ItemManager.class), service, prefs);
			realPanel.setClock(() -> NOW);
		});
		final BpmCommands dev = new BpmCommands(realPanel, service, gson);

		JsonObject state = send(dev, "state");
		assertEquals("ITEMS", state.get("view").getAsString());
		final JsonObject history = state.getAsJsonObject("bankHistory");
		assertEquals("7d", history.get("range").getAsString());
		assertFalse("the panel's own line does not carry it", state.getAsJsonObject("panel").has("view"));

		state = send(dev, "view=history");
		assertEquals("HISTORY", state.get("view").getAsString());
		assertEquals(2, state.getAsJsonObject("bankHistory").get("readings").getAsInt());
		assertEquals(TODAY.toString(), state.getAsJsonObject("bankHistory").get("last").getAsString());
		assertEquals(BankPriceMovementPanel.CARD_HISTORY, state.getAsJsonObject("panel").get("card").getAsString());

		state = send(dev, "range=90d");
		assertEquals("90d", state.getAsJsonObject("bankHistory").get("range").getAsString());
		assertEquals("the card's window is untouched", "D1", state.getAsJsonObject("panel").get("window").getAsString());

		state = send(dev, "window=30d");
		assertEquals("the card's window moves the chart", "30d",
			state.getAsJsonObject("bankHistory").get("range").getAsString());

		// The bank history map holds only strings, integers and nulls (9.11).
		final LinkedHashMap<String, Object> map = realPanel.bankHistoryState();
		for (Object v : map.values())
		{
			assertTrue(String.valueOf(v), v == null || v instanceof String || v instanceof Integer);
		}
	}
}
