package com.example;

import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.*;

public class WillowFinderOverlay extends Overlay
{
    private final Client client;
    private final WillowFinderPlugin plugin;
    private final WillowFinderConfig config;

    @Inject
    public WillowFinderOverlay(Client client, WillowFinderPlugin plugin, WillowFinderConfig config)
    {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        for (WillowFinderPlugin.WillowTreeData tree : plugin.getWillowTrees())
        {
            // Färbe den Baum
            Shape clickbox = tree.gameObject.getClickbox();
            if (clickbox != null)
            {
                graphics.setColor(config.highlightColor());
                graphics.setStroke(new BasicStroke(2));
                graphics.draw(clickbox);
            }

            // Zeige Distanz
            if (config.showDistance())
            {
                String text = tree.distance + " tiles";
                Point textLocation = tree.canvasPoint;

                graphics.setColor(Color.BLACK);
                graphics.drawString(text, textLocation.x + 1, textLocation.y + 1);
                graphics.setColor(Color.WHITE);
                graphics.drawString(text, textLocation.x, textLocation.y);
            }
        }

		// Banks (mit gelber Farbe)
		for (WillowFinderPlugin.BankData bank : plugin.getBanks())
		{
			Shape clickbox = bank.gameObject.getClickbox();
			if (clickbox != null)
			{
				graphics.setColor(config.bankHighlightColor());
				graphics.setStroke(new BasicStroke(3));
				graphics.draw(clickbox);
			}

			if (config.showDistance())
			{
				String text = bank.distance + " tiles (" + bank.type + ")";
				Point textLocation = bank.canvasPoint;
				
				graphics.setColor(Color.BLACK);
				graphics.drawString(text, textLocation.x + 1, textLocation.y + 1);
				graphics.setColor(Color.YELLOW);
				graphics.drawString(text, textLocation.x, textLocation.y);
			}
		}

		return null;
	}
}