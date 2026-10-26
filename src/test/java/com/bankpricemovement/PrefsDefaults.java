package com.bankpricemovement;

import java.awt.Color;
import javax.annotation.Nullable;

/**
 * A {@link BankPriceMovementPanel.Prefs} with nothing to remember: every load answers null ("nothing stored", which the
 * panel reads as the shipped default) and every save writes nothing. Release 1.2.2 made the interface's methods
 * abstract - the plugin answers all of them over {@code ConfigManager} - so the tests' memories, recorders and
 * throwaway seams extend this and override only what they look at.
 */
abstract class PrefsDefaults implements BankPriceMovementPanel.Prefs
{
	@Nullable
	@Override
	public HeroVisibility loadHero()
	{
		return null;
	}

	@Override
	public void saveHero(HeroVisibility visibility)
	{
	}

	@Nullable
	@Override
	public ViewOptions loadOptions()
	{
		return null;
	}

	@Override
	public void saveOptions(ViewOptions options)
	{
	}

	@Nullable
	@Override
	public BandPresets loadPresets()
	{
		return null;
	}

	@Override
	public void savePresets(BandPresets presets)
	{
	}

	@Nullable
	@Override
	public Boolean loadFoldOpen()
	{
		return null;
	}

	@Override
	public void saveFoldOpen(boolean open)
	{
	}

	@Nullable
	@Override
	public SidebarView loadStartTab()
	{
		return null;
	}

	@Override
	public void saveStartTab(SidebarView tab)
	{
	}

	@Nullable
	@Override
	public Boolean loadIncludeLegacy()
	{
		return null;
	}

	@Override
	public void saveIncludeLegacy(boolean include)
	{
	}

	@Nullable
	@Override
	public Color loadUpColour()
	{
		return null;
	}

	@Override
	public void saveUpColour(Color colour)
	{
	}

	@Nullable
	@Override
	public Color loadDownColour()
	{
		return null;
	}

	@Override
	public void saveDownColour(Color colour)
	{
	}

	@Nullable
	@Override
	public Color loadSlotUpColour()
	{
		return null;
	}

	@Override
	public void saveSlotUpColour(Color colour)
	{
	}

	@Nullable
	@Override
	public Color loadSlotDownColour()
	{
		return null;
	}

	@Override
	public void saveSlotDownColour(Color colour)
	{
	}

	@Nullable
	@Override
	public Boolean loadSingleChartColour()
	{
		return null;
	}

	@Override
	public void saveSingleChartColour(boolean single)
	{
	}

	@Nullable
	@Override
	public Color loadChartColour()
	{
		return null;
	}

	@Override
	public void saveChartColour(Color colour)
	{
	}

	@Nullable
	@Override
	public Boolean loadHideAmounts()
	{
		return null;
	}

	@Override
	public void saveHideAmounts(boolean hide)
	{
	}

	@Nullable
	@Override
	public Boolean loadShowAlchRows()
	{
		return null;
	}

	@Override
	public void saveShowAlchRows(boolean show)
	{
	}

	@Nullable
	@Override
	public ChangeText loadChangeText()
	{
		return null;
	}

	@Override
	public void saveChangeText(ChangeText text)
	{
	}

	@Nullable
	@Override
	public ChangeText loadHistoryText()
	{
		return null;
	}

	@Override
	public void saveHistoryText(ChangeText text)
	{
	}

	@Nullable
	@Override
	public BankHistoryRange loadHistoryRange()
	{
		return null;
	}

	@Override
	public void saveHistoryRange(BankHistoryRange range)
	{
	}
}
