package de.lostesburger.mySqlPlayerBridge.Managers.Edit;

import de.craftcore.craftcore.global.mysql.MySqlError;
import de.craftcore.craftcore.global.mysql.MySqlManager;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.lostesburger.mySqlPlayerBridge.Main;
import de.lostesburger.mySqlPlayerBridge.Utils.Chat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EditGuiManager implements Listener {
    private static final EditGuiManager INSTANCE = new EditGuiManager();
    private final ConcurrentHashMap<UUID, EditSession> sessions = new ConcurrentHashMap<>();

    public static EditGuiManager getInstance(){
        return INSTANCE;
    }

    private EditGuiManager(){
        Bukkit.getPluginManager().registerEvents(this, Main.getInstance());
    }

    public void openInventoryEditor(Player admin, UUID targetUuid, String targetName, String type, String serializedData){
        if(!admin.isOnline()){
            return;
        }
        if(Main.nbtSerializer == null){
            admin.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "NBT serializer missing"));
            return;
        }

        ItemStack[] items;
        int expectedSize;
        if(serializedData == null || serializedData.isEmpty()){
            expectedSize = getDefaultSize(type);
            items = new ItemStack[expectedSize];
        }else {
            try {
                items = Main.nbtSerializer.deserialize(serializedData);
            } catch (Exception e) {
                admin.sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "deserialize"));
                return;
            }
            expectedSize = items.length;
        }

        int inventorySize = getInventorySize(expectedSize, type);
        String title = Chat.getMessageWithoutPrefix("edit-gui-title")
                .replace("{type}", type)
                .replace("{player}", targetName);

        Inventory inventory = Bukkit.createInventory(admin, inventorySize, title);
        for (int i = 0; i < items.length && i < inventorySize; i++){
            inventory.setItem(i, items[i]);
        }

        sessions.put(admin.getUniqueId(), new EditSession(admin.getUniqueId(), targetUuid, targetName, type, inventory, expectedSize));
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event){
        UUID adminUuid = event.getPlayer().getUniqueId();
        EditSession session = sessions.get(adminUuid);
        if(session == null) return;
        if(!event.getInventory().equals(session.inventory)) return;
        sessions.remove(adminUuid);

        if(Main.nbtSerializer == null){
            event.getPlayer().sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "NBT serializer missing"));
            return;
        }

        ItemStack[] contents = extractContents(event.getInventory(), session.expectedSize);
        String serialized;
        try {
            serialized = Main.nbtSerializer.serialize(contents);
        } catch (Exception e) {
            event.getPlayer().sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "serialize"));
            return;
        }

        Scheduler.runAsync(() -> {
            MySqlManager manager = Main.mySqlConnectionHandler.getManager();
            String table = getTableForInventoryType(session.type);
            String column = getColumnForInventoryType(session.type);
            try {
                manager.setOrUpdateEntry(table, Map.of("uuid", session.targetUuid.toString()), Map.of(column, serialized));
            } catch (MySqlError e) {
                Scheduler.run(() -> event.getPlayer().sendMessage(Chat.getMessage("edit-db-error")), Main.getInstance());
                throw new RuntimeException(e);
            }

            Scheduler.run(() -> {
                Player target = Bukkit.getPlayer(session.targetUuid);
                if(target != null){
                    applyToPlayer(target, session.type, contents);
                }
                event.getPlayer().sendMessage(Chat.getMessage("edit-success"));
            }, Main.getInstance());
        }, Main.getInstance());
    }

    private void applyToPlayer(Player target, String type, ItemStack[] contents){
        switch (type){
            case "inventory" -> target.getInventory().setContents(contents);
            case "armor" -> target.getInventory().setArmorContents(contents);
            case "enderchest" -> target.getEnderChest().setContents(contents);
        }
    }

    private ItemStack[] extractContents(Inventory inventory, int length){
        ItemStack[] result = new ItemStack[length];
        ItemStack[] all = inventory.getContents();
        for (int i = 0; i < length && i < all.length; i++){
            result[i] = all[i];
        }
        return result;
    }

    private int getDefaultSize(String type){
        return switch (type) {
            case "armor" -> 4;
            case "enderchest" -> 27;
            default -> 36;
        };
    }

    private int getInventorySize(int expectedSize, String type){
        if(type.equals("armor")){
            return 9;
        }
        int rows = (expectedSize + 8) / 9;
        return Math.max(9, rows * 9);
    }

    private String getTableForInventoryType(String type){
        return switch (type) {
            case "inventory" -> Main.TABLE_NAME_INVENTORY;
            case "armor" -> Main.TABLE_NAME_ARMOR;
            case "enderchest" -> Main.TABLE_NAME_ENDERCHEST;
            default -> "";
        };
    }

    private String getColumnForInventoryType(String type){
        return switch (type) {
            case "inventory" -> "inventory";
            case "armor" -> "armor";
            case "enderchest" -> "enderchest";
            default -> "";
        };
    }

    private static class EditSession {
        private final UUID adminUuid;
        private final UUID targetUuid;
        private final String targetName;
        private final String type;
        private final Inventory inventory;
        private final int expectedSize;

        private EditSession(UUID adminUuid, UUID targetUuid, String targetName, String type, Inventory inventory, int expectedSize){
            this.adminUuid = adminUuid;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.type = type;
            this.inventory = inventory;
            this.expectedSize = expectedSize;
        }
    }
}
