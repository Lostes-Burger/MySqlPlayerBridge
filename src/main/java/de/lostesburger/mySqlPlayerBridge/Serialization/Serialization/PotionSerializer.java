package de.lostesburger.mySqlPlayerBridge.Serialization.Serialization;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class PotionSerializer {
    private final Gson gson = new Gson();
    public PotionSerializer() {

    }

    public String serialize(Player player) {
        JsonArray effectsArray = new JsonArray();

        for (PotionEffect effect : player.getActivePotionEffects()) {
            JsonObject effectObj = new JsonObject();
            effectObj.addProperty("type", effect.getType().getName());
            effectObj.addProperty("duration", effect.getDuration());
            effectObj.addProperty("amplifier", effect.getAmplifier());
            effectObj.addProperty("ambient", effect.isAmbient());
            effectObj.addProperty("particles", effect.hasParticles());
            effectObj.addProperty("icon", effect.hasIcon());

            effectsArray.add(effectObj);
        }

        String jsonString = gson.toJson(effectsArray);
        return Base64.getEncoder().encodeToString(jsonString.getBytes(StandardCharsets.UTF_8));
    }

    public List<PotionEffect> deserialize(String base64) {
        List<PotionEffect> potionEffects = new ArrayList<>();


        String jsonString = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        JsonArray effectsArray = gson.fromJson(jsonString, JsonArray.class);

        for (int i = 0; i < effectsArray.size(); i++) {
            JsonObject obj = effectsArray.get(i).getAsJsonObject();

            PotionEffectType type = PotionEffectType.getByName(obj.get("type").getAsString());
            if (type == null) continue;

            int duration = obj.get("duration").getAsInt();
            int amplifier = obj.get("amplifier").getAsInt();
            boolean ambient = obj.get("ambient").getAsBoolean();
            boolean particles = obj.get("particles").getAsBoolean();
            boolean icon = obj.get("icon").getAsBoolean();

            PotionEffect effect = new PotionEffect(type, duration, amplifier, ambient, particles, icon);
            potionEffects.add(effect);
        }

        return potionEffects;
    }
}
