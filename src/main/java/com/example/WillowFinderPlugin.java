package com.example;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import javax.inject.Inject;
import java.awt.*;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@PluginDescriptor(
	name = "Willow Finder",
	description = "Färbt Willow Bäume, Bank Booths und exportiert Daten via WebSocket",
	tags = {"woodcutting", "willow", "websocket", "bank"}
)
public class WillowFinderPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private WillowFinderConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private WillowFinderOverlay overlay;

	private SimpleWebSocketServer wsServer;
	private final Gson gson = new Gson();

	// Willow Tree IDs
	private static final int[] WILLOW_TREE_IDS = {10829, 10831, 10833};
	
	// Bank Booth IDs (verschiedene Banken)
	private static final int[] BANK_BOOTH_IDS = {
		10355, 10356, 10357, 10358, // Standard booths
		11338, 12798, 14367, 19230, // Mehr Varianten
		24914, 25808, 27254, 29085,
		34752, 35647, 36786, 37474
	};
	
	// Bank Chest IDs
	private static final int[] BANK_CHEST_IDS = {
		4483, 8981, 14382, 21301,
		27254, 34752
	};

    // Motherlode Mine IDs (Beispiele für die fehlenden Symbole)
    private static final int[] ORE_VEIN_IDS = {26661, 26662, 26663, 26664};
    private static final int[] HOPPER_IDS = {26674};
    private static final int[] SACK_IDS = {26688};
    private static final int[] BROKEN_STRUT_IDS = {26669, 26670};

    private String lastJsonData = "{}"; // Speichert den letzten gültigen Stand

    @Getter
	private final List<WillowTreeData> willowTrees = new ArrayList<>();

    @Getter
    private final List<MiningObjectData> miningObjects = new ArrayList<>(); // Für MLM

	@Getter
	private final List<BankData> banks = new ArrayList<>();

	@Getter
	private final List<InventoryItemData> inventoryItems = new ArrayList<>();

	@Getter
	private final List<String> recentChatMessages = new ArrayList<>();
	private static final int MAX_CHAT_MESSAGES = 10;

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		
		if (config.enableWebSocket())
		{
			startWebSocketServer();
			log.info("Willow Finder gestartet! WebSocket auf ws://localhost:8765");
		}
		else
		{
			log.info("Willow Finder gestartet! (WebSocket deaktiviert)");
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		overlayManager.remove(overlay);
		willowTrees.clear();
		banks.clear();
		inventoryItems.clear();
		
		if (wsServer != null)
		{
			try
			{
				wsServer.stop();
				log.info("WebSocket Server gestoppt");
			}
			catch (InterruptedException e)
			{
				log.error("WebSocket stop failed", e);
			}
		}
	}

    private int logTimer = 0;

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        willowTrees.clear();
        banks.clear();
        inventoryItems.clear();

        scanGameObjects();
        updateInventory();

        logTimer++;
        if (logTimer >= 10)
        {
            logNearbyObjects();
            logTimer = 0;
        }

        // Generiere JSON auf dem Client-Thread und speichere es
        lastJsonData = buildJsonData();

        if (wsServer != null && config.enableWebSocket())
        {
            // Sende den bereits generierten String
            try
            {
                for (WebSocket conn : wsServer.getConnections())
                {
                    if (conn.isOpen())
                    {
                        conn.send(lastJsonData);
                    }
                }
            }
            catch (Exception e)
            {
                log.error("WebSocket broadcast failed", e);
            }
        }
    }

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		String message = chatMessage.getMessage();
		String sender = chatMessage.getName();
		
		// Format: [Sender] Message oder [System] Message
		String formattedMessage;
		if (sender != null && !sender.isEmpty())
		{
			formattedMessage = "[" + sender + "] " + message;
		}
		else
		{
			formattedMessage = "[System] " + message;
		}
		
		recentChatMessages.add(formattedMessage);
		
		// Behalte nur letzte 10 Nachrichten
		if (recentChatMessages.size() > MAX_CHAT_MESSAGES)
		{
			recentChatMessages.remove(0);
		}
	}

	// Optimiert: Scanne alle Tiles nur EINMAL
    private void scanGameObjects()
    {
        Scene scene = client.getScene();
        Tile[][][] tiles = scene.getTiles();
        int plane = client.getPlane();
        Player player = client.getLocalPlayer();
        if (player == null) return;

        // Alle Listen leeren
        willowTrees.clear();
        miningObjects.clear();
        banks.clear();

        LocalPoint playerLocation = player.getLocalLocation();

        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                // --- 1. WALL OBJECTS (Speziell für Ore Veins) ---
                WallObject wall = tile.getWallObject();
                if (wall != null && isOreVein(wall.getId()))
                {
                    addMiningObject(wall.getId(), wall.getLocalLocation(), playerLocation, "vein");
                }

                // --- 2. GAME OBJECTS (Bäume, Banken, MLM-Maschinen) ---
                GameObject[] objects = tile.getGameObjects();
                if (objects != null)
                {
                    for (GameObject go : objects)
                    {
                        if (go == null) continue;
                        int id = go.getId();

                        // Prüfe: Ist es ein Willow Baum?
                        if (isWillowTree(id))
                        {
                            WorldPoint wp = WorldPoint.fromLocal(client, go.getLocalLocation());
                            net.runelite.api.Point rlP = Perspective.localToCanvas(client, go.getLocalLocation(), plane);
                            if (rlP != null) {
                                willowTrees.add(new WillowTreeData(go, wp, new java.awt.Point(rlP.getX(), rlP.getY()),
                                        go.getLocalLocation().distanceTo(playerLocation)/128, id));
                            }
                        }
                        // Prüfe: Ist es ein Mining Objekt?
                        else if (isOreVein(id)) addMiningObject(id, go.getLocalLocation(), playerLocation, "vein");
                        else if (isHopper(id)) addMiningObject(id, go.getLocalLocation(), playerLocation, "hopper");
                        else if (isSack(id)) addMiningObject(id, go.getLocalLocation(), playerLocation, "sack");
                        else if (isBrokenStrut(id)) addMiningObject(id, go.getLocalLocation(), playerLocation, "strut");

                            // Prüfe: Ist es eine Bank?
                        else if (isBankObject(id))
                        {
                            WorldPoint wp = WorldPoint.fromLocal(client, go.getLocalLocation());
                            net.runelite.api.Point rlP = Perspective.localToCanvas(client, go.getLocalLocation(), plane);
                            if (rlP != null) {
                                banks.add(new BankData(go, wp, new java.awt.Point(rlP.getX(), rlP.getY()),
                                        go.getLocalLocation().distanceTo(playerLocation)/128, "chest"));
                            }
                        }
                    }
                }
            }
        }
    }

    // Hilfsmethode um Mining-Objekte zur Liste hinzuzufügen
    private void addMiningObject(int id, LocalPoint lp, LocalPoint pLoc, String type) {
        WorldPoint wp = WorldPoint.fromLocal(client, lp);
        net.runelite.api.Point rlP = Perspective.localToCanvas(client, lp, client.getPlane());
        if (rlP != null && wp != null) {
            miningObjects.add(new MiningObjectData(null, wp, new java.awt.Point(rlP.getX(), rlP.getY()), lp.distanceTo(pLoc)/128, id, type));
        }
    }

	private void updateInventory()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);
		if (inventory == null) return;

		Widget inventoryWidget = client.getWidget(WidgetInfo.INVENTORY);
		if (inventoryWidget == null || inventoryWidget.isHidden()) return;

		Item[] items = inventory.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item.getId() == -1 || item.getId() == 0) continue; // Leerer Slot

			ItemComposition itemComp = client.getItemDefinition(item.getId());
			
			// Berechne Canvas-Position des Slots
			Rectangle bounds = inventoryWidget.getChild(slot).getBounds();
			int canvasX = (int) bounds.getCenterX();
			int canvasY = (int) bounds.getCenterY();
			
			InventoryItemData itemData = new InventoryItemData(
				item.getId(),
				itemComp.getName(),
				item.getQuantity(),
				slot,
				canvasX,
				canvasY
			);
			
			inventoryItems.add(itemData);
		}
	}

	private boolean isWillowTree(int objectId)
	{
		for (int id : WILLOW_TREE_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}

	private boolean isBankObject(int objectId)
	{
		return isBankBooth(objectId) || isBankChest(objectId);
	}

	private boolean isBankBooth(int objectId)
	{
		for (int id : BANK_BOOTH_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}

	private boolean isBankChest(int objectId)
	{
		for (int id : BANK_CHEST_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}
	
	private boolean isOreVein(int objectId)
	{
		for (int id : ORE_VEIN_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}
	
	private boolean isHopper(int objectId)
	{
		for (int id : HOPPER_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}
	
	private boolean isSack(int objectId)
	{
		for (int id : SACK_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}
	
	private boolean isBrokenStrut(int objectId)
	{
		for (int id : BROKEN_STRUT_IDS)
		{
			if (id == objectId) return true;
		}
		return false;
	}
	
	private String getOreVeinState(int objectId)
	{
		switch (objectId)
		{
			case 26661:
				return "full";
			case 26662:
				return "medium";
			case 26663:
				return "low";
			case 26664:
				return "depleted";
			default:
				return "unknown";
		}
	}

	private String getTreeState(int objectId)
	{
		switch (objectId)
		{
			case 10829:
				return "full";
			case 10831:
				return "chopped";
			case 10833:
				return "stump";
			default:
				return "unknown";
		}
	}

	private String getPlayerActivity(int animationId)
	{
		// Bekannte Animation IDs
		switch (animationId)
		{
			case -1:
				return "idle";
			case 867:   // Woodcutting (bronze-rune axe)
			case 2846:  // Woodcutting (dragon axe)
			case 870:   // Woodcutting (3rd age axe)
			case 875:   // Woodcutting (crystal axe)
			case 10251: // Woodcutting (infernal axe)
				return "woodcutting";
			case 621:   // Fishing (net)
			case 622:   // Fishing (bait/fly)
			case 623:   // Fishing (cage)
			case 618:   // Fishing (harpoon)
				return "fishing";
			case 896:   // Mining
			case 7282:  // Mining (dragon pickaxe)
				return "mining";
			case 422:   // Combat (slash)
			case 423:   // Combat (stab)
			case 401:   // Combat (crush)
			case 711:   // Combat (magic)
			case 426:   // Combat (ranged)
				return "combat";
			case 832:   // Cooking
				return "cooking";
			case 713:   // Crafting
				return "crafting";
			case 8980:  // Smithing (smelting)
				return "smithing";
			default:
				return "unknown_" + animationId;
		}
	}

	private boolean isBankOpen()
	{
		// Bank Container sichtbar?
		return client.getItemContainer(InventoryID.BANK) != null;
	}

	private boolean isDialogOpen()
	{
		// NPC Dialog Widget
		Widget npcDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (npcDialog != null && !npcDialog.isHidden())
		{
			return true;
		}
		
		// Player Dialog (Click to continue)
		Widget playerDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
		if (playerDialog != null && !playerDialog.isHidden())
		{
			return true;
		}
		
		// Option Dialog (Multiple choices)
		Widget optionDialog = client.getWidget(WidgetInfo.DIALOG_OPTION);
		if (optionDialog != null && !optionDialog.isHidden())
		{
			return true;
		}
		
		return false;
	}

	private boolean isShopOpen()
	{
		// Shop Widget ID: 300,75 oder 300,76
		Widget shopWidget = client.getWidget(300, 75);
		if (shopWidget == null)
		{
			shopWidget = client.getWidget(300, 76);
		}
		return shopWidget != null && !shopWidget.isHidden();
	}

    private void logNearbyObjects()
    {
        Player player = client.getLocalPlayer();
        if (player == null) return;

        LocalPoint localPoint = player.getLocalLocation();
        int sceneX = localPoint.getSceneX();
        int sceneY = localPoint.getSceneY();
        int plane = client.getPlane();

        Tile[][][] tiles = client.getScene().getTiles();

        // Scannt 1 Tile in jede Richtung (3x3 Bereich um den Spieler)
        for (int x = sceneX - 1; x <= sceneX + 1; x++)
        {
            for (int y = sceneY - 1; y <= sceneY + 1; y++)
            {
                if (x < 0 || x >= 104 || y < 0 || y >= 104) continue;

                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                // GameObject (Bäume, Erze, Banken)
                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects != null)
                {
                    for (GameObject go : gameObjects)
                    {
                        if (go != null && go.getSceneMinLocation().getX() == x && go.getSceneMinLocation().getY() == y)
                        {
                            ObjectComposition comp = client.getObjectDefinition(go.getId());
                            log.info("[Nearby Object] Name: {}, ID: {}, Type: GameObject", comp.getName(), go.getId());
                        }
                    }
                }

                // WallObject (Türen, Mauern, manchmal auch Erze in Wänden)
                WallObject wall = tile.getWallObject();
                if (wall != null)
                {
                    ObjectComposition comp = client.getObjectDefinition(wall.getId());
                    log.info("[Nearby Object] Name: {}, ID: {}, Type: WallObject", comp.getName(), wall.getId());
                }

                // DecorativeObject (Deko an Wänden)
                DecorativeObject deco = tile.getDecorativeObject();
                if (deco != null)
                {
                    ObjectComposition comp = client.getObjectDefinition(deco.getId());
                    log.info("[Nearby Object] Name: {}, ID: {}, Type: DecorativeObject", comp.getName(), deco.getId());
                }

                // GroundObject (Dinge die flach am Boden liegen)
                GroundObject ground = tile.getGroundObject();
                if (ground != null)
                {
                    ObjectComposition comp = client.getObjectDefinition(ground.getId());
                    log.info("[Nearby Object] Name: {}, ID: {}, Type: GroundObject", comp.getName(), ground.getId());
                }
            }
        }
    }

	private String getDialogText()
	{
		// NPC Dialog
		Widget npcDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
		if (npcDialog != null && !npcDialog.isHidden())
		{
			Widget npcName = client.getWidget(WidgetInfo.DIALOG_NPC_NAME);
			String name = npcName != null ? npcName.getText() : "NPC";
			return "[" + name + "] " + npcDialog.getText();
		}
		
		// Player Dialog
		Widget playerDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
		if (playerDialog != null && !playerDialog.isHidden())
		{
			return "[Player] " + playerDialog.getText();
		}
		
		// Options Dialog
		Widget optionDialog = client.getWidget(WidgetInfo.DIALOG_OPTION);
		if (optionDialog != null && !optionDialog.isHidden())
		{
			StringBuilder options = new StringBuilder("[Options] ");
			Widget[] children = optionDialog.getChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && child.getText() != null && !child.getText().isEmpty())
					{
						options.append(child.getText()).append(" | ");
					}
				}
			}
			return options.toString();
		}
		
		return null;
	}

	private String buildJsonData()
	{
		Map<String, Object> data = new HashMap<>();
		
		Player player = client.getLocalPlayer();
		if (player == null) return gson.toJson(data);

		WorldPoint playerPos = player.getWorldLocation();
		
		// Player Info (erweitert)
		Map<String, Object> playerData = new HashMap<>();
		playerData.put("x", playerPos.getX());
		playerData.put("y", playerPos.getY());
		playerData.put("plane", playerPos.getPlane());
		playerData.put("health", client.getBoostedSkillLevel(Skill.HITPOINTS));
		playerData.put("max_health", client.getRealSkillLevel(Skill.HITPOINTS));
		playerData.put("prayer", client.getBoostedSkillLevel(Skill.PRAYER));
		playerData.put("run_energy", client.getEnergy() / 100);
		playerData.put("woodcutting_level", client.getRealSkillLevel(Skill.WOODCUTTING));
		
		// Player Activity
		int animationId = player.getAnimation();
		String activity = getPlayerActivity(animationId);
		playerData.put("animation_id", animationId);
		playerData.put("activity", activity);
		playerData.put("is_idle", animationId == -1);
		playerData.put("is_moving", player.getIdlePoseAnimation() != player.getPoseAnimation());
		playerData.put("interacting_with", player.getInteracting() != null ? player.getInteracting().getName() : null);
		
		// Interface/Dialog Detection
		playerData.put("in_bank", isBankOpen());
		playerData.put("in_dialog", isDialogOpen());
		playerData.put("in_shop", isShopOpen());
		// Note: Typing detection nicht verfügbar in aktueller API
		
		// Interaktion Details
		Actor interacting = player.getInteracting();
		if (interacting != null)
		{
			Map<String, Object> interactData = new HashMap<>();
			interactData.put("name", interacting.getName());
			interactData.put("type", interacting instanceof NPC ? "npc" : "player");
			interactData.put("health_ratio", interacting.getHealthRatio());
			interactData.put("health_scale", interacting.getHealthScale());
			
			if (interacting instanceof NPC)
			{
				NPC npc = (NPC) interacting;
				interactData.put("npc_id", npc.getId());
				interactData.put("combat_level", npc.getCombatLevel());
			}
			
			playerData.put("interaction_details", interactData);
		}
		
		// Dialog Text wenn vorhanden
		String dialogText = getDialogText();
		if (dialogText != null)
		{
			playerData.put("dialog_text", dialogText);
		}
		
		data.put("player", playerData);

        // --- WILLOW TREES ---
        List<Map<String, Object>> treesData = new ArrayList<>();
        for (WillowTreeData tree : willowTrees) {
            Map<String, Object> m = new HashMap<>();
            m.put("world_x", tree.worldPoint.getX());
            m.put("world_y", tree.worldPoint.getY());
            m.put("canvas_x", tree.canvasPoint.x);
            m.put("canvas_y", tree.canvasPoint.y);
            m.put("distance", tree.distance);
            m.put("state", getTreeState(tree.objectId));
            treesData.add(m);
        }
        data.put("willow_trees", treesData);

        // --- MINING OBJECTS (Mining) ---
        List<Map<String, Object>> oreVeins = new ArrayList<>();
        List<Map<String, Object>> hoppers = new ArrayList<>();
        List<Map<String, Object>> sacks = new ArrayList<>();
        List<Map<String, Object>> struts = new ArrayList<>();

        for (MiningObjectData mo : miningObjects) {
            Map<String, Object> m = new HashMap<>();
            m.put("world_x", mo.worldPoint.getX());
            m.put("world_y", mo.worldPoint.getY());
            m.put("canvas_x", mo.canvasPoint.x);
            m.put("canvas_y", mo.canvasPoint.y);
            m.put("distance", mo.distance);

            if (mo.type.equals("vein")) {
                m.put("state", getOreVeinState(mo.objectId));
                oreVeins.add(m);
            }
            else if (mo.type.equals("hopper")) hoppers.add(m);
            else if (mo.type.equals("sack")) sacks.add(m);
            else if (mo.type.equals("strut")) struts.add(m);
        }
        data.put("ore_veins", oreVeins);
        data.put("hoppers", hoppers);
        data.put("sacks", sacks);
        data.put("broken_struts", struts);
		
		// Banks
		List<Map<String, Object>> banksData = new ArrayList<>();
		for (BankData bank : banks)
		{
			Map<String, Object> bankMap = new HashMap<>();
			bankMap.put("world_x", bank.worldPoint.getX());
			bankMap.put("world_y", bank.worldPoint.getY());
			bankMap.put("canvas_x", bank.canvasPoint.x);
			bankMap.put("canvas_y", bank.canvasPoint.y);
			bankMap.put("distance", bank.distance);
			bankMap.put("type", bank.type);
			banksData.add(bankMap);
		}
		data.put("banks", banksData);
		data.put("bank_count", banks.size());
		
		// Inventory (mit Canvas-Koordinaten)
		List<Map<String, Object>> inventoryData = new ArrayList<>();
		for (InventoryItemData item : inventoryItems)
		{
			Map<String, Object> itemMap = new HashMap<>();
			itemMap.put("id", item.itemId);
			itemMap.put("name", item.name);
			itemMap.put("quantity", item.quantity);
			itemMap.put("slot", item.slot);
			itemMap.put("canvas_x", item.canvasX);
			itemMap.put("canvas_y", item.canvasY);
			inventoryData.add(itemMap);
		}
		data.put("inventory", inventoryData);
		data.put("inventory_count", inventoryItems.size());
		data.put("inventory_full", inventoryItems.size() >= 28);
		
		// Chat Messages
		data.put("chat_messages", new ArrayList<>(recentChatMessages));
		
		data.put("timestamp", System.currentTimeMillis());
		
		return gson.toJson(data);
	}

	private void startWebSocketServer()
	{
		wsServer = new SimpleWebSocketServer(new InetSocketAddress(8765));
		wsServer.setReuseAddr(true);  // Erlaube Port-Reuse
		wsServer.setConnectionLostTimeout(10);  // Timeout nach 10 Sekunden
		wsServer.start();
	}

	private class SimpleWebSocketServer extends WebSocketServer
	{
		public SimpleWebSocketServer(InetSocketAddress address)
		{
			super(address);
			setReuseAddr(true);
		}

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake)
        {
            log.info("WebSocket Client verbunden: {}", conn.getRemoteSocketAddress());
            try
            {
                // SENDE NUR DEN CACHE, NICHT buildJsonData() AUFRUFEN!
                conn.send(lastJsonData);
            }
            catch (Exception e)
            {
                log.error("Failed to send initial data", e);
            }
        }

		@Override
		public void onClose(WebSocket conn, int code, String reason, boolean remote)
		{
			log.info("WebSocket Client getrennt: {} (Code: {}, Reason: {})", 
				conn.getRemoteSocketAddress(), code, reason);
		}

		@Override
		public void onMessage(WebSocket conn, String message)
		{
			log.debug("Message: {}", message);
		}

		@Override
		public void onError(WebSocket conn, Exception ex)
		{
			if (conn != null)
			{
				log.error("WebSocket Error from {}: {}", conn.getRemoteSocketAddress(), ex.getMessage());
				// Schließe fehlerhafte Verbindung
				try
				{
					conn.close();
				}
				catch (Exception e)
				{
					// Ignore
				}
			}
			else
			{
				log.error("WebSocket Server Error", ex);
			}
		}

		@Override
		public void onStart()
		{
			log.info("WebSocket Server bereit auf Port 8765");
			setConnectionLostTimeout(10);  // 10 Sekunden Timeout
		}
	}

	@Provides
	WillowFinderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WillowFinderConfig.class);
	}

	// Datenklassen
	public static class WillowTreeData
	{
		public final GameObject gameObject;
		public final WorldPoint worldPoint;
		public final java.awt.Point canvasPoint;
		public final int distance;
		public final int objectId;

		public WillowTreeData(GameObject gameObject, WorldPoint worldPoint, java.awt.Point canvasPoint, int distance, int objectId)
		{
			this.gameObject = gameObject;
			this.worldPoint = worldPoint;
			this.canvasPoint = canvasPoint;
			this.distance = distance;
			this.objectId = objectId;
		}
	}

	public static class BankData
	{
		public final GameObject gameObject;
		public final WorldPoint worldPoint;
		public final java.awt.Point canvasPoint;
		public final int distance;
		public final String type;

		public BankData(GameObject gameObject, WorldPoint worldPoint, java.awt.Point canvasPoint, int distance, String type)
		{
			this.gameObject = gameObject;
			this.worldPoint = worldPoint;
			this.canvasPoint = canvasPoint;
			this.distance = distance;
			this.type = type;
		}
	}

	public static class InventoryItemData
	{
		public final int itemId;
		public final String name;
		public final int quantity;
		public final int slot;
		public final int canvasX;
		public final int canvasY;

		public InventoryItemData(int itemId, String name, int quantity, int slot, int canvasX, int canvasY)
		{
			this.itemId = itemId;
			this.name = name;
			this.quantity = quantity;
			this.slot = slot;
			this.canvasX = canvasX;
			this.canvasY = canvasY;
		}
	}
	
	public static class MiningObjectData
	{
		public final GameObject gameObject;
		public final WorldPoint worldPoint;
		public final java.awt.Point canvasPoint;
		public final int distance;
		public final int objectId;
		public final String type;

		public MiningObjectData(GameObject gameObject, WorldPoint worldPoint, java.awt.Point canvasPoint, int distance, int objectId, String type)
		{
			this.gameObject = gameObject;
			this.worldPoint = worldPoint;
			this.canvasPoint = canvasPoint;
			this.distance = distance;
			this.objectId = objectId;
			this.type = type;
		}
	}
}

