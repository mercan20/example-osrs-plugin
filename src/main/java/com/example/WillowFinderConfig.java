package com.example;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import java.awt.Color;

@ConfigGroup("willowfinder")
public interface WillowFinderConfig extends Config
{
	@ConfigItem(
		keyName = "highlightColor",
		name = "Willow Highlight Farbe",
		description = "Farbe für Willow Baum Highlights"
	)
	default Color highlightColor()
	{
		return Color.GREEN;
	}

	@ConfigItem(
		keyName = "bankHighlightColor",
		name = "Bank Highlight Farbe",
		description = "Farbe für Bank Highlights"
	)
	default Color bankHighlightColor()
	{
		return Color.YELLOW;
	}

	@ConfigItem(
		keyName = "showDistance",
		name = "Distanz anzeigen",
		description = "Zeigt die Entfernung zu Objekten"
	)
	default boolean showDistance()
	{
		return true;
	}

	@ConfigItem(
		keyName = "enableWebSocket",
		name = "WebSocket aktivieren",
		description = "Exportiert Daten via WebSocket (Port 8765)"
	)
	default boolean enableWebSocket()
	{
		return true;
	}
}