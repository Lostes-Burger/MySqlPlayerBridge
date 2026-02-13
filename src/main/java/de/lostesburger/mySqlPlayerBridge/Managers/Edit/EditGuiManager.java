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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
        ItemStack[] originalItems;
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
        if(type.equals("offhand") && expectedSize < 37){
            ItemStack[] expanded = new ItemStack[37];
            System.arraycopy(items, 0, expanded, 0, items.length);
            items = expanded;
            expectedSize = expanded.length;
        }
        originalItems = Arrays.copyOf(items, expectedSize);

        int inventorySize = getInventorySize(expectedSize, type);
        String title = Chat.getMessageWithoutPrefix("edit-gui-title")
                .replace("{type}", type)
                .replace("{player}", targetName);

        Inventory inventory = Bukkit.createInventory(admin, inventorySize, title);
        Set<Integer> blockedSlots = Set.of();
        Integer offhandSlot = null;

        if(type.equals("inventory")){
            int storageSize = Math.min(36, items.length);
            for (int i = 0; i < storageSize; i++){
                inventory.setItem(i, items[i]);
            }
        }else if(type.equals("armor")){
            int armorSize = Math.min(items.length, 4);
            for (int i = 0; i < armorSize; i++){
                inventory.setItem(i, items[i]);
            }
            blockedSlots = new HashSet<>();
            for (int slot = 4; slot < inventorySize; slot++){
                blockedSlots.add(slot);
            }
            placeBarriers(inventory, blockedSlots);
        }else if(type.equals("offhand")){
            offhandSlot = 4;
            if(items.length > 36){
                inventory.setItem(offhandSlot, items[items.length - 1]);
            }
            blockedSlots = getOffhandBlockedSlots();
            placeBarriers(inventory, blockedSlots);
        }else {
            for (int i = 0; i < items.length && i < inventorySize; i++){
                inventory.setItem(i, items[i]);
            }
        }

        sessions.put(admin.getUniqueId(), new EditSession(admin.getUniqueId(), targetUuid, targetName, type, inventory, expectedSize,
                offhandSlot, blockedSlots, originalItems));
        admin.openInventory(inventory);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event){
        UUID adminUuid = event.getPlayer().getUniqueId();
        EditSession session = sessions.get(adminUuid);
        if(session == null) return;
        if(!event.getView().getTopInventory().equals(session.inventory)) return;
        sessions.remove(adminUuid);

        if(Main.nbtSerializer == null){
            event.getPlayer().sendMessage(Chat.getMessage("edit-invalid-value").replace("{reason}", "NBT serializer missing"));
            return;
        }

        ItemStack[] contents = extractContents(event.getView().getTopInventory(), session);
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

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event){
        UUID adminUuid = event.getWhoClicked().getUniqueId();
        EditSession session = sessions.get(adminUuid);
        if(session == null) return;
        if(!event.getView().getTopInventory().equals(session.inventory)) return;

        int rawSlot = event.getRawSlot();
        if(rawSlot < 0 || rawSlot >= session.inventory.getSize()){
            return;
        }
        if(session.blockedSlots.contains(rawSlot)){
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event){
        UUID adminUuid = event.getWhoClicked().getUniqueId();
        EditSession session = sessions.get(adminUuid);
        if(session == null) return;
        if(!event.getView().getTopInventory().equals(session.inventory)) return;

        for (int rawSlot : event.getRawSlots()){
            if(rawSlot >= 0 && rawSlot < session.inventory.getSize() && session.blockedSlots.contains(rawSlot)){
                event.setCancelled(true);
                return;
            }
        }
    }

    private void applyToPlayer(Player target, String type, ItemStack[] contents){
        switch (type){
            case "inventory" -> target.getInventory().setContents(contents);
            case "armor" -> target.getInventory().setArmorContents(contents);
            case "enderchest" -> target.getEnderChest().setContents(contents);
            case "offhand" -> {
                if(contents.length > 36){
                    target.getInventory().setItemInOffHand(contents[contents.length - 1]);
                }
            }
        }
    }

    private ItemStack[] extractContents(Inventory inventory, EditSession session){
        if(session.type.equals("inventory")){
            ItemStack[] result = Arrays.copyOf(session.originalItems, session.expectedSize);
            int storageSize = Math.min(36, result.length);
            for (int i = 0; i < storageSize; i++){
                result[i] = inventory.getItem(i);
            }
            return result;
        }

        if(session.type.equals("offhand")){
            ItemStack[] result = Arrays.copyOf(session.originalItems, session.expectedSize);
            if(result.length > 36){
                result[result.length - 1] = inventory.getItem(session.offhandSlot);
            }
            return result;
        }

        ItemStack[] result = Arrays.copyOf(session.originalItems, session.expectedSize);
        for (int i = 0; i < session.expectedSize && i < inventory.getSize(); i++){
            result[i] = inventory.getItem(i);
        }
        return result;
    }

    private int getDefaultSize(String type){
        return switch (type) {
            case "armor" -> 4;
            case "enderchest" -> 27;
            case "offhand" -> 37;
            default -> 36;
        };
    }

    private int getInventorySize(int expectedSize, String type){
        if(type.equals("armor")){
            return 9;
        }
        if(type.equals("inventory")){
            return 36;
        }
        if(type.equals("offhand")){
            return 9;
        }
        int rows = (expectedSize + 8) / 9;
        return Math.max(9, rows * 9);
    }

    private void placeBarriers(Inventory inventory, Set<Integer> blockedSlots){
        if(blockedSlots.isEmpty()){
            return;
        }
        ItemStack barrier = buildBarrier();
        for (int slot : blockedSlots){
            inventory.setItem(slot, barrier);
        }
    }

    private ItemStack buildBarrier(){
        ItemStack barrier = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta meta = barrier.getItemMeta();
        if(meta != null){
            meta.setDisplayName("§cLocked");
            barrier.setItemMeta(meta);
        }
        return barrier;
    }

    private Set<Integer> getOffhandBlockedSlots(){
        Set<Integer> blocked = new java.util.HashSet<>();
        for (int slot = 0; slot < 9; slot++){
            if(slot != 4){
                blocked.add(slot);
            }
        }
        return blocked;
    }

    private String getTableForInventoryType(String type){
        return switch (type) {
            case "inventory" -> Main.TABLE_NAME_INVENTORY;
            case "armor" -> Main.TABLE_NAME_ARMOR;
            case "enderchest" -> Main.TABLE_NAME_ENDERCHEST;
            case "offhand" -> Main.TABLE_NAME_INVENTORY;
            default -> "";
        };
    }

    private String getColumnForInventoryType(String type){
        return switch (type) {
            case "inventory" -> "inventory";
            case "armor" -> "armor";
            case "enderchest" -> "enderchest";
            case "offhand" -> "inventory";
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
        private final Integer offhandSlot;
        private final Set<Integer> blockedSlots;
        private final ItemStack[] originalItems;

        private EditSession(UUID adminUuid, UUID targetUuid, String targetName, String type, Inventory inventory, int expectedSize,
                            Integer offhandSlot, Set<Integer> blockedSlots, ItemStack[] originalItems){
            this.adminUuid = adminUuid;
            this.targetUuid = targetUuid;
            this.targetName = targetName;
            this.type = type;
            this.inventory = inventory;
            this.expectedSize = expectedSize;
            this.offhandSlot = offhandSlot;
            this.blockedSlots = blockedSlots;
            this.originalItems = originalItems;
        }
    }
}
