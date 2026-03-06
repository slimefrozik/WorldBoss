package com.worldboss;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

public enum BossType {
    ZOMBIE_GIANT("zombie_giant", "Зомби-гигант", EntityType.ZOMBIE,
            List.of("PLAINS", "FOREST", "TAIGA", "SAVANNA"), Material.ZOMBIE_HEAD),
    IFRIT_TITAN("ifrit_titan", "Ифрит-титан", EntityType.BLAZE,
            List.of("DESERT", "BADLANDS", "ERODED_BADLANDS", "WOODED_BADLANDS"), Material.BLAZE_ROD),
    RAID_ARCHMAGE("raid_archmage", "Архимаг рейда", EntityType.EVOKER,
            List.of("PLAINS", "FOREST", "DARK_FOREST"), Material.TOTEM_OF_UNDYING),
    GIANT_PHANTOM("giant_phantom", "Гигантский фантом", EntityType.PHANTOM,
            List.of("!*_OCEAN", "!RIVER"), Material.PHANTOM_MEMBRANE),
    GIANT_SHULKER("giant_shulker", "Гигантский шалкер", EntityType.SHULKER,
            List.of("!*_OCEAN", "!RIVER"), Material.SHULKER_SHELL),
    FOREST_GUARDIAN("forest_guardian", "Лесной страж", EntityType.IRON_GOLEM,
            List.of("FOREST", "BIRCH_FOREST", "JUNGLE"), Material.EMERALD);

    private final String id;
    private final String displayName;
    private final EntityType entityType;
    private final List<String> defaultBiomes;
    private final Material lootMaterial;

    BossType(String id, String displayName, EntityType entityType, List<String> defaultBiomes, Material lootMaterial) {
        this.id = id;
        this.displayName = displayName;
        this.entityType = entityType;
        this.defaultBiomes = defaultBiomes;
        this.lootMaterial = lootMaterial;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public EntityType entityType() {
        return entityType;
    }

    public List<String> defaultBiomes() {
        return defaultBiomes;
    }

    public Material lootMaterial() {
        return lootMaterial;
    }

    public static BossType fromId(String id) {
        for (BossType value : values()) {
            if (value.id.equalsIgnoreCase(id)) {
                return value;
            }
        }
        return ZOMBIE_GIANT;
    }
}
