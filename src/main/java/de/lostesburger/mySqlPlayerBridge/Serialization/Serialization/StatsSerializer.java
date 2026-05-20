package de.lostesburger.mySqlPlayerBridge.Serialization.Serialization;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Statistic;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.Material;
import org.bukkit.Bukkit;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class StatsSerializer {
    private final Gson gson = new Gson();
    private final boolean DEBUG = true;

    public StatsSerializer() {}

    public String serialize(Player player) {
        JsonArray statsArray = new JsonArray();

        for (Statistic stat : Statistic.values()) {
            try {
                JsonObject statObj = new JsonObject();
                statObj.addProperty("type", stat.name());

                switch (stat.getType()) {
                    case UNTYPED -> {
                        int value = player.getStatistic(stat);
                        if (value != 0) {
                            statObj.addProperty("value", value);
                            statsArray.add(statObj);
                        }
                    }
                    case BLOCK -> {
                        for (Material mat : Material.values()) {
                            if (mat.isBlock()) {
                                int value = player.getStatistic(stat, mat);
                                if (value != 0) {
                                    JsonObject blockObj = statObj.deepCopy();
                                    blockObj.addProperty("subType", mat.name());
                                    blockObj.addProperty("value", value);
                                    statsArray.add(blockObj);
                                }
                            }
                        }
                    }
                    case ITEM -> {
                        for (Material mat : Material.values()) {
                            if (mat.isItem()) {
                                int value = player.getStatistic(stat, mat);
                                if (value != 0) {
                                    JsonObject itemObj = statObj.deepCopy();
                                    itemObj.addProperty("subType", mat.name());
                                    itemObj.addProperty("value", value);
                                    statsArray.add(itemObj);
                                }
                            }
                        }
                    }
                    case ENTITY -> {
                        for (EntityType type : EntityType.values()) {
                            try {
                                int value = player.getStatistic(stat, type);
                                if (value != 0) {
                                    JsonObject entityObj = statObj.deepCopy();
                                    entityObj.addProperty("subType", type.name());
                                    entityObj.addProperty("value", value);
                                    statsArray.add(entityObj);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }

            } catch (Exception e) {
                if (DEBUG) Bukkit.getLogger().warning("[StatsSerializer] Fehler bei Statistik: " + stat.name());
            }
        }

        String jsonString = gson.toJson(statsArray);
        return Base64.getEncoder().encodeToString(jsonString.getBytes(StandardCharsets.UTF_8));
    }

    public JsonArray deserialize(String base64, Player player, boolean apply) {
        String jsonString = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        JsonArray statsArray = gson.fromJson(jsonString, JsonArray.class);

        for (int i = 0; i < statsArray.size(); i++) {
            JsonObject statObj = statsArray.get(i).getAsJsonObject();
            String typeName = statObj.get("type").getAsString();
            int value = statObj.get("value").getAsInt();

            Statistic stat;
            try {
                stat = Statistic.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                if (DEBUG) Bukkit.getLogger().warning("[StatsSerializer] Unbekannte Statistik: " + typeName);
                continue;
            }

            if (!apply) return null;

                try {
                    switch (stat.getType()) {
                        case UNTYPED -> {
                            player.setStatistic(stat, value);
                        }
                        case BLOCK -> {
                            Material block = Material.matchMaterial(statObj.get("subType").getAsString());
                            if (block != null && block.isBlock()){
                                player.setStatistic(stat, block, value);
                            }
                        }
                        case ITEM -> {
                            Material item = Material.matchMaterial(statObj.get("subType").getAsString());
                            if (item != null && item.isItem()){
                                player.setStatistic(stat, item, value);
                            }
                        }
                        case ENTITY -> {
                            try {
                                EntityType entity = EntityType.valueOf(statObj.get("subType").getAsString());
                                player.setStatistic(stat, entity, value);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    if (DEBUG) Bukkit.getLogger().warning("[StatsSerializer] Fehler beim Setzen von " + stat.name() + ": " + e.getMessage());
                }

        }

        return statsArray;
    }
}
