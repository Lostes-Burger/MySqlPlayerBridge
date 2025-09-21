package de.lostesburger.mySqlPlayerBridge.Serialization.NBTSerialization;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class NBTSerializer {

    private final Method convertItemArrayToNBT;
    private final Method convertNBTToItemArray;
    private final Constructor<?> nbtContainerConstructor;
    private final Method toStringMethod;

    public NBTSerializer() throws Exception {
        Plugin nbtApiPlugin = Bukkit.getPluginManager().getPlugin("NBTAPI");
        if (nbtApiPlugin == null) {
            throw new IllegalStateException("NBTAPI Plugin is not loaded!");
        }

        ClassLoader nbtApiClassLoader = nbtApiPlugin.getClass().getClassLoader();

        Class<?> nbtItemClass = Class.forName("de.tr7zw.nbtapi.NBTItem", true, nbtApiClassLoader);
        convertItemArrayToNBT = nbtItemClass.getDeclaredMethod("convertItemArraytoNBT", ItemStack[].class);
        Class<?> nbtCompoundClass = Class.forName("de.tr7zw.nbtapi.NBTCompound", true, nbtApiClassLoader);
        convertNBTToItemArray = nbtItemClass.getDeclaredMethod("convertNBTtoItemArray", nbtCompoundClass);

        Class<?> nbtContainerClass = Class.forName("de.tr7zw.nbtapi.NBTContainer", true, nbtApiClassLoader);
        nbtContainerConstructor = nbtContainerClass.getConstructor(String.class);
        toStringMethod = nbtContainerClass.getMethod("toString");
    }

    public String serialize(ItemStack[] items) throws Exception {
        Object nbtContainer = convertItemArrayToNBT.invoke(null, (Object) items);
        String json = (String) toStringMethod.invoke(nbtContainer);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public ItemStack[] deserialize(String base64) throws Exception {
        String json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        Object nbtContainer = nbtContainerConstructor.newInstance(json);
        Object items = convertNBTToItemArray.invoke(null, nbtContainer);
        return (ItemStack[]) items;
    }
}
