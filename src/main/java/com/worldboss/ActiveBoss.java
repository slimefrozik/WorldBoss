package com.worldboss;

import org.bukkit.Location;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ActiveBoss {
    public UUID entityId;
    public BossType bossType;
    public Location spawnLocation;
    public long spawnedAt;
    public long despawnAt;
    public final Set<UUID> participants = new HashSet<>();
    public final Map<UUID, Double> damage = new HashMap<>();
    public final Set<Location> temporaryBlocks = new HashSet<>();
}
