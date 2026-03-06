package com.worldboss;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import java.util.List;

public enum BossType {
    ZOMBIE("zombie", EntityType.ZOMBIE, List.of("!*_OCEAN", "!RIVER"), Material.DIAMOND),
    FIRE("fire", EntityType.BLAZE, List.of("DESERT", "BADLANDS", "ERODED_BADLANDS", "WOODED_BADLANDS"), Material.BLAZE_ROD);

    private final String id;
    private final EntityType entityType;
    private final List<String> defaultBiomes;
    private final Material lootMaterial;

    BossType(String id, EntityType entityType, List<String> defaultBiomes, Material lootMaterial) {
        this.id = id;
        this.entityType = entityType;
        this.defaultBiomes = defaultBiomes;
        this.lootMaterial = lootMaterial;
    }

    public String id() {
        return id;
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
        return ZOMBIE;
    }
}
