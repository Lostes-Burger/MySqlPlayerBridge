package de.lostesburger.mySqlPlayerBridge.Serialization.Serialization;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import de.craftcore.craftcore.global.minecraftVersion.Minecraft;
import de.craftcore.craftcore.global.scheduler.Scheduler;
import de.craftcore.craftcore.global.scheduler.SchedulerException;
import de.lostesburger.mySqlPlayerBridge.Main;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.NamespacedKey;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class AdvancementSerializer {
    private final Gson gson = new Gson();
    private final boolean DEBUG = false;

    public String serialize(Player player) {
        JsonArray advancementsArray = new JsonArray();
        Iterator<Advancement> advancements = Bukkit.getServer().advancementIterator();

        while (advancements.hasNext()) {
            Advancement advancement = advancements.next();
            AdvancementProgress progress = player.getAdvancementProgress(advancement);

            JsonObject effectObj = new JsonObject();
            effectObj.addProperty("type", advancement.getKey().toString());
            if(DEBUG) System.out.println("[AdvancementSerializer] Advancement namespaced key (serialize): "+advancement.getKey().toString());
            effectObj.addProperty("done", progress.isDone());
            effectObj.addProperty("awardedCriteria", String.join(",", progress.getAwardedCriteria()));

            if (progress.isDone() || !progress.getAwardedCriteria().isEmpty()) {
                advancementsArray.add(effectObj);
            }
        }

        String jsonString = gson.toJson(advancementsArray);
        return Base64.getEncoder().encodeToString(jsonString.getBytes(StandardCharsets.UTF_8));
    }

    public JsonArray deserialize(String base64, Player player, boolean applyAdvancements) {
        String jsonString = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        JsonArray advancementsArray = gson.fromJson(jsonString, JsonArray.class);

        for (int i = 0; i < advancementsArray.size(); i++) {
            JsonObject advancementObj = advancementsArray.get(i).getAsJsonObject();
            String advancementKeyString = advancementObj.get("type").getAsString();
            boolean done = advancementObj.get("done").getAsBoolean();
            String awardedCriteriaString = advancementObj.get("awardedCriteria").getAsString();

            if (advancementKeyString.startsWith("minecraft:")) {
                advancementKeyString = advancementKeyString.substring("minecraft:".length());
            }


            NamespacedKey advancementKey = new NamespacedKey("minecraft", advancementKeyString);
            if(DEBUG) System.out.println("[AdvancementSerializer] Advancement namespaced key (deserialize): "+advancementKey.toString());
            if (advancementKey == null) return null;

            Advancement advancement = Bukkit.getAdvancement(advancementKey);
            if (advancement == null) return null;

            AdvancementProgress progress = player.getAdvancementProgress(advancement);
            Set<String> awardedCriteria = new HashSet<>(Arrays.asList(awardedCriteriaString.split(",")));

            if (applyAdvancements) {
                if(!Minecraft.isFolia()){
                    Scheduler.run(() -> {
                        for (String criterion : awardedCriteria) {
                            progress.awardCriteria(criterion);
                        }
                    }, Main.getInstance());
                }else {
                    try {
                        Scheduler.runRegionalScheduler(() -> {
                            for (String criterion : awardedCriteria) {
                                progress.awardCriteria(criterion);
                            }
                        }, Main.getInstance(), player.getLocation());
                    } catch (SchedulerException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return advancementsArray;
    }
}
