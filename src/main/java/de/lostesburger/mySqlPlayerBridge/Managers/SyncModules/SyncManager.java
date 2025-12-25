package de.lostesburger.mySqlPlayerBridge.Managers.SyncModules;

import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Advancement.AdvancementDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Armor.ArmorDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.EXP.ExperienceDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Effect.EffectDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Enderchest.EnderchestDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Gamemode.GamemodeDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Health.HealthDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Hotbar.HotbarSlotSelectionDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Inventory.InventoryDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Location.LocationDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Money.MoneyDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Saturation.SaturationDataManager;
import de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Stats.StatsDataManager;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.AdvancementSerializer;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.PotionSerializer;
import de.lostesburger.mySqlPlayerBridge.Serialization.Serialization.StatsSerializer;

public class SyncManager {
    public static LocationDataManager locationDataManager;
    public static ExperienceDataManager experienceDataManager;
    public static GamemodeDataManager gamemodeDataManager;
    public static InventoryDataManager inventoryDataManager;
    public static ArmorDataManager armorDataManager;
    public static MoneyDataManager moneyDataManager;
    public static HealthDataManager healthDataManager;
    public static EnderchestDataManager enderchestDataManager;

    public static EffectDataManager effectDataManager;
    public static AdvancementDataManager advancementDataManager;
    public static StatsDataManager statsDataManager;
    public static HotbarSlotSelectionDataManager hotbarSlotSelectionDataManager;
    public static SaturationDataManager saturationDataManager;


    public static PotionSerializer potionSerializer;
    public static AdvancementSerializer advancementSerializer;
    public static StatsSerializer statsSerializer;

    public SyncManager() {
        locationDataManager = new LocationDataManager();
        experienceDataManager = new ExperienceDataManager();
        gamemodeDataManager = new GamemodeDataManager();
        inventoryDataManager = new InventoryDataManager();
        armorDataManager = new ArmorDataManager();
        moneyDataManager = new MoneyDataManager();
        healthDataManager = new HealthDataManager();
        enderchestDataManager = new EnderchestDataManager();

        potionSerializer = new PotionSerializer();
        effectDataManager = new de.lostesburger.mySqlPlayerBridge.Managers.SyncModules.Effect.EffectDataManager();
        advancementSerializer = new AdvancementSerializer();
        advancementDataManager = new AdvancementDataManager();
        statsSerializer = new StatsSerializer();
        statsDataManager = new StatsDataManager();
        hotbarSlotSelectionDataManager = new HotbarSlotSelectionDataManager();
        saturationDataManager = new SaturationDataManager();
    }
}
