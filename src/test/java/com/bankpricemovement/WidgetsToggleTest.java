package com.bankpricemovement;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.swing.JLabel;
import org.junit.Test;
import static com.bankpricemovement.SidebarViewPanelTest.onEdt;
import static com.bankpricemovement.SidebarViewPanelTest.press;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link Widgets#toggle}'s own contract (addendum AU, plan 7.2 item 1), which the panel cannot observe - there a press
 * on the view already in force writes nothing anyway: a LEFT press on the UNLIT half runs {@code onPress} with its
 * index; a press on the lit half and a right press run nothing; the toggle never relights itself.
 */
public class WidgetsToggleTest
{
	@Test
	public void onlyALeftPressOnTheUnlitHalfRunsThePress() throws Exception
	{
		onEdt(() ->
		{
			final List<Integer> pressed = new ArrayList<>();
			final Widgets.Toggle t = Widgets.toggle("A", "B", pressed::add);
			t.setLit(0);

			press(half(t, 0), MouseEvent.BUTTON1);
			assertEquals("a press on the lit half does nothing", Collections.emptyList(), pressed);

			press(half(t, 1), MouseEvent.BUTTON1);
			assertEquals(Collections.singletonList(1), pressed);
			assertTrue("the toggle never relights itself - the caller says", t.isLit(0));

			press(half(t, 1), MouseEvent.BUTTON3);
			assertEquals("a right press is not a press", Collections.singletonList(1), pressed);

			t.setLit(1);
			press(half(t, 1), MouseEvent.BUTTON1);
			press(half(t, 0), MouseEvent.BUTTON1);
			assertEquals("lit the other way round", Arrays.asList(1, 0), pressed);
		});
	}

	@Test
	public void aToggleWithNoPressIsOnlyALight() throws Exception
	{
		onEdt(() ->
		{
			final Widgets.Toggle t = Widgets.toggle("A", "B", null);
			press(half(t, 1), MouseEvent.BUTTON1);
			assertTrue(t.isLit(0));
		});
	}

	private static JLabel half(Widgets.Toggle t, int i)
	{
		return (JLabel) t.getComponent(i);
	}
}
