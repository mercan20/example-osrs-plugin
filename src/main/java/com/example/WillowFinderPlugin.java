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
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
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

    @Getter
    private final List<WillowTreeData> willowTrees = new ArrayList<>();

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

    @Subscribe
    public void onGameTick(GameTick gameTick)
    {
        willowTrees.clear();
        banks.clear();
        inventoryItems.clear();

        // EINE Loop für alle GameObject-Scans
        scanGameObjects();
        updateInventory();

        if (wsServer != null && config.enableWebSocket())
        {
            String json = buildJsonData();
            wsServer.broadcast(json);
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

        LocalPoint playerLocation = player.getLocalLocation();

        // Vermeide Duplikate - speichere bereits gesehene Positionen
        Map<WorldPoint, Boolean> seenWillowPositions = new HashMap<>();
        Map<WorldPoint, Boolean> seenBankPositions = new HashMap<>();

        // Eine Loop für alles
        for (int x = 0; x < Constants.SCENE_SIZE; x++)
        {
            for (int y = 0; y < Constants.SCENE_SIZE; y++)
            {
                Tile tile = tiles[plane][x][y];
                if (tile == null) continue;

                GameObject[] gameObjects = tile.getGameObjects();
                if (gameObjects == null) continue;

                for (GameObject gameObject : gameObjects)
                {
                    if (gameObject == null) continue;

                    int objectId = gameObject.getId();

                    // Prüfe ob Willow ODER Bank
                    boolean isWillow = isWillowTree(objectId);
                    boolean isBank = isBankObject(objectId);

                    if (!isWillow && !isBank) continue; // Skip irrelevante Objekte

                    // Berechne gemeinsame Daten nur einmal
                    LocalPoint localPoint = gameObject.getLocalLocation();
                    WorldPoint worldPoint = WorldPoint.fromLocal(client, localPoint);
                    if (worldPoint == null) continue;

                    // Skip Duplikate (mehrere Object IDs am gleichen Tile)
                    if (isWillow && seenWillowPositions.containsKey(worldPoint)) continue;
                    if (isBank && seenBankPositions.containsKey(worldPoint)) continue;

                    net.runelite.api.Point rlPoint = Perspective.localToCanvas(client, localPoint, plane);
                    if (rlPoint == null) continue;
                    java.awt.Point canvasPoint = new java.awt.Point(rlPoint.getX(), rlPoint.getY());

                    int distance = localPoint.distanceTo(playerLocation) / 128;

                    // Füge zu entsprechender Liste hinzu
                    if (isWillow)
                    {
                        seenWillowPositions.put(worldPoint, true);
                        willowTrees.add(new WillowTreeData(
                                gameObject,
                                worldPoint,
                                canvasPoint,
                                distance,
                                objectId
                        ));
                    }

                    if (isBank)
                    {
                        seenBankPositions.put(worldPoint, true);
                        String type = isBankBooth(objectId) ? "booth" : "chest";
                        banks.add(new BankData(
                                gameObject,
                                worldPoint,
                                canvasPoint,
                                distance,
                                type
                        ));
                    }
                }
            }
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

        data.put("player", playerData);

        // Willow Trees
        List<Map<String, Object>> treesData = new ArrayList<>();
        for (WillowTreeData tree : willowTrees)
        {
            Map<String, Object> treeMap = new HashMap<>();
            treeMap.put("world_x", tree.worldPoint.getX());
            treeMap.put("world_y", tree.worldPoint.getY());
            treeMap.put("canvas_x", tree.canvasPoint.x);
            treeMap.put("canvas_y", tree.canvasPoint.y);
            treeMap.put("distance", tree.distance);
            treeMap.put("object_id", tree.objectId);
            treeMap.put("state", getTreeState(tree.objectId));
            treesData.add(treeMap);
        }
        data.put("willow_trees", treesData);
        data.put("tree_count", willowTrees.size());

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
        wsServer.start();
    }

    private class SimpleWebSocketServer extends WebSocketServer
    {
        public SimpleWebSocketServer(InetSocketAddress address)
        {
            super(address);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake)
        {
            log.info("WebSocket Client verbunden: {}", conn.getRemoteSocketAddress());
            conn.send(buildJsonData());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote)
        {
            log.info("WebSocket Client getrennt");
        }

        @Override
        public void onMessage(WebSocket conn, String message)
        {
            log.debug("Message: {}", message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex)
        {
            log.error("WebSocket Error", ex);
        }

        @Override
        public void onStart()
        {
            log.info("WebSocket Server bereit auf Port 8765");
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
}