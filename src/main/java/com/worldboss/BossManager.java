package com.worldboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import com.worldboss.economy.EconomyService;
import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class BossManager {
    public static final String BOSS_META = "worldboss_system";
    private final JavaPlugin plugin;
    private final EconomyService economyService;
    private final Set<UUID> readyPlayers = new HashSet<>();
    private final Map<UUID, Long> respawnBlockedUntil = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingClaims = new HashMap<>();
    private final Set<DayOfWeek> skippedDays = new HashSet<>();
    private ActiveBoss activeBoss;
    private BukkitTask controlTask;
    private BukkitTask abilityTask;
    private int spawnsThisWeek = 0;
    private int weekIndex = -1;

    public BossManager(JavaPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
    }

    public void startTickTasks() {
        controlTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickBossControl, 20L, 20L);
        abilityTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runBossAbilities, 20L * 10L, 20L * 10L);
    }

    public void shutdown() {
        if (controlTask != null) controlTask.cancel();
        if (abilityTask != null) abilityTask.cancel();
        clearTemporaryBlocks();
    }

    public boolean setReady(Player player) {
        if (!isSpawnDayAllowed()) return false;
        if (isSkippedToday()) return false;
        readyPlayers.add(player.getUniqueId());
        if (readyPlayers.size() >= plugin.getConfig().getInt("voting.min-ready", 4)) {
            startEvent(false);
        }
        return true;
    }

    public void setNotReady(Player player) {
        readyPlayers.remove(player.getUniqueId());
    }

    public int getReadyCount() {
        return readyPlayers.size();
    }

    public void skipToday() {
        skippedDays.add(LocalDate.now().getDayOfWeek());
    }

    public boolean isSkippedToday() {
        return skippedDays.contains(LocalDate.now().getDayOfWeek());
    }

    public boolean startEvent(boolean forced) {
        rotateWeekCounter();
        if (forced && activeBoss != null) {
            removeBoss(false);
        }
        if (!forced) {
            if (activeBoss != null) return false;
            if (!isSpawnDayAllowed() || isSkippedToday()) return false;
            if (spawnsThisWeek >= plugin.getConfig().getInt("schedule.max-per-week", 2)) return false;
        }

        World world = Bukkit.getWorld(plugin.getConfig().getString("spawn.world", "world"));
        if (world == null) return false;

        BossType bossType = nextBossType();
        Location spawn = findSpawnPoint(world, bossType);
        if (spawn == null) {
            plugin.getLogger().warning("Cannot find valid spawn location for boss");
            return false;
        }

        spawnBoss(bossType, spawn);
        if (!forced) {
            spawnsThisWeek++;
        }
        readyPlayers.clear();
        return true;
    }

    public boolean respawnBoss() {
        if (activeBoss == null) return false;
        Location location = activeBoss.spawnLocation.clone();
        BossType type = activeBoss.bossType;
        removeBoss(false);
        spawnBoss(type, location);
        return true;
    }

    private void spawnBoss(BossType bossType, Location spawn) {
        World world = spawn.getWorld();
        LivingEntity entity = (LivingEntity) world.spawnEntity(spawn, bossType.entityType());
        entity.customName(Component.text("Мировой Босс: " + bossType.displayName(), NamedTextColor.RED));
        entity.setCustomNameVisible(true);
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        entity.setMetadata(BOSS_META, new FixedMetadataValue(plugin, true));

        double hp = plugin.getConfig().getDouble("bosses." + bossType.id() + ".health", 600.0);
        if (entity.getAttribute(Attribute.MAX_HEALTH) != null) {
            entity.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            entity.setHealth(hp);
        }

        activeBoss = new ActiveBoss();
        activeBoss.entityId = entity.getUniqueId();
        activeBoss.bossType = bossType;
        activeBoss.spawnLocation = spawn.clone();
        activeBoss.spawnedAt = System.currentTimeMillis();
        activeBoss.despawnAt = activeBoss.spawnedAt + plugin.getConfig().getLong("event.duration-minutes", 60L) * 60_000L;

        Bukkit.broadcast(Component.text("⚠ В мире появился босс! Координаты: " +
                spawn.getBlockX() + " " + spawn.getBlockY() + " " + spawn.getBlockZ(), NamedTextColor.GOLD));
        saveState();
    }

    public void removeBoss(boolean announce) {
        LivingEntity boss = getBossEntity();
        if (boss != null && !boss.isDead()) {
            boss.remove();
        }
        clearTemporaryBlocks();
        activeBoss = null;
        if (announce) Bukkit.broadcast(Component.text("Босс исчез.", NamedTextColor.GRAY));
        saveState();
    }

    public ActiveBoss getActiveBoss() {
        return activeBoss;
    }

    public LivingEntity getBossEntity() {
        if (activeBoss == null) return null;
        Entity entity = Bukkit.getEntity(activeBoss.entityId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private void tickBossControl() {
        rotateWeekCounter();
        long now = System.currentTimeMillis();
        respawnBlockedUntil.entrySet().removeIf(e -> e.getValue() <= now);

        if (activeBoss == null) return;
        LivingEntity boss = getBossEntity();
        if (boss == null || boss.isDead()) {
            removeBoss(false);
            return;
        }

        if (now >= activeBoss.despawnAt) {
            removeBoss(true);
            return;
        }

        double maxRadius = plugin.getConfig().getDouble("boss.behavior.leash-radius", 50.0);
        if (boss.getLocation().distanceSquared(activeBoss.spawnLocation) > maxRadius * maxRadius) {
            boss.teleport(activeBoss.spawnLocation);
        }

        if (activeBoss.bossType == BossType.FOREST_GUARDIAN && boss.isInWaterOrBubbleColumn()) {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 20 * 3, 2, false, false, true));
            return;
        }

        if (boss.isInWaterOrBubbleColumn() || boss.getVelocity().lengthSquared() < 0.0001) {
            boss.teleport(activeBoss.spawnLocation);
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 15, 0, false, false, true));
        }
    }

    private void runBossAbilities() {
        if (activeBoss == null) return;
        LivingEntity boss = getBossEntity();
        if (boss == null || boss.isDead()) return;

        switch (activeBoss.bossType) {
            case ZOMBIE_GIANT -> runZombieGiantAbilities(boss);
            case IFRIT_TITAN -> runIfritTitanAbilities(boss);
            case RAID_ARCHMAGE -> runRaidArchmageAbilities(boss);
            case GIANT_PHANTOM -> runGiantPhantomAbilities(boss);
            case GIANT_SHULKER -> runGiantShulkerAbilities(boss);
            case FOREST_GUARDIAN -> runForestGuardianAbilities(boss);
        }
    }

    private void runZombieGiantAbilities(LivingEntity boss) {
        dealAoeDamage(boss, 8.0, 14.0);
        summonMinions(boss, EntityType.ZOMBIE, 5, 8, 10.0);
        launchProjectile(boss, EntityType.SMALL_FIREBALL, 1.0);
        double hpPart = boss.getHealth() / boss.getAttribute(Attribute.MAX_HEALTH).getValue();
        int phase = (int) ((1.0 - hpPart) / 0.25);
        if (phase > 0) {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 20 * 15, Math.min(3, phase), false, false, true));
        }
    }

    private void runIfritTitanAbilities(LivingEntity boss) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < random.nextInt(10, 16); i++) {
            Location at = boss.getLocation().clone().add(random.nextDouble(-8, 8), 12, random.nextDouble(-8, 8));
            Fireball rain = (Fireball) boss.getWorld().spawnEntity(at, EntityType.FIREBALL);
            rain.setShooter(boss);
            rain.setYield(0f);
            rain.setIsIncendiary(false);
            rain.setDirection(new Vector(0, -1, 0));
        }

        createTemporaryBlocks(boss.getLocation());
        launchProjectile(boss, EntityType.SMALL_FIREBALL, 1.8);
        if (random.nextDouble() < 0.35) {
            boss.setVelocity(new Vector(0, 0.9, 0));
        }
    }

    private void runRaidArchmageAbilities(LivingEntity boss) {
        summonMinions(boss, EntityType.PILLAGER, 3, 5, 16.0);
        for (int i = 0; i < 4; i++) {
            Location fangsAt = boss.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-4, 4), 0, ThreadLocalRandom.current().nextDouble(-4, 4));
            boss.getWorld().spawnEntity(fangsAt, EntityType.EVOKER_FANGS);
        }
        boss.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 20 * 8, 1, false, false, true));
        long raidersAlive = boss.getWorld().getNearbyEntities(boss.getLocation(), 20, 12, 20).stream()
                .filter(entity -> entity.getType() == EntityType.PILLAGER)
                .count();
        if (raidersAlive == 0) {
            boss.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 8, 1, false, false, true));
        }
    }

    private void runGiantPhantomAbilities(LivingEntity boss) {
        dealAoeDamage(boss, 10.0, 10.0);
        summonMinions(boss, EntityType.PHANTOM, 2, 4, 18.0);
        boss.getWorld().getNearbyPlayers(boss.getLocation(), 25).forEach(player -> {
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 20 * 4, 0, false, false, true));
            registerParticipant(player.getUniqueId());
        });
        if (ThreadLocalRandom.current().nextDouble() < 0.35) {
            Location blink = activeBoss.spawnLocation.clone().add(ThreadLocalRandom.current().nextDouble(-14, 14), 20, ThreadLocalRandom.current().nextDouble(-14, 14));
            boss.teleport(blink);
        }
    }

    private void runGiantShulkerAbilities(LivingEntity boss) {
        for (int i = 0; i < 4; i++) {
            launchProjectile(boss, EntityType.SHULKER_BULLET, 1.0);
        }
        summonMinions(boss, EntityType.SHULKER, 1, 2, 12.0);
        boss.getWorld().getNearbyPlayers(boss.getLocation(), 12).forEach(player -> {
            player.addPotionEffect(new PotionEffect(PotionEffectType.LEVITATION, 20 * 3, 1, false, false, true));
            registerParticipant(player.getUniqueId());
        });
        if (ThreadLocalRandom.current().nextDouble() < 0.25) {
            Location blink = activeBoss.spawnLocation.clone().add(ThreadLocalRandom.current().nextDouble(-8, 8), ThreadLocalRandom.current().nextDouble(2, 8), ThreadLocalRandom.current().nextDouble(-8, 8));
            boss.teleport(blink);
        }
    }

    private void runForestGuardianAbilities(LivingEntity boss) {
        dealAoeDamage(boss, 8.0, 12.0);
        boss.getWorld().getNearbyPlayers(boss.getLocation(), 8).forEach(player -> {
            Vector knockback = player.getLocation().toVector().subtract(boss.getLocation().toVector()).normalize().multiply(1.2).setY(0.4);
            player.setVelocity(knockback);
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 20 * 5, 1, false, false, true));
        });
        launchProjectile(boss, EntityType.SNOWBALL, 1.5);
    }

    private void dealAoeDamage(LivingEntity boss, double radius, double damage) {
        boss.getWorld().getNearbyPlayers(boss.getLocation(), radius).forEach(player -> {
            player.damage(damage, boss);
            registerParticipant(player.getUniqueId());
        });
    }

    private void summonMinions(LivingEntity boss, EntityType type, int minCount, int maxCount, double aggroRadius) {
        int count = ThreadLocalRandom.current().nextInt(minCount, maxCount + 1);
        for (int i = 0; i < count; i++) {
            Location loc = boss.getLocation().clone().add(ThreadLocalRandom.current().nextDouble(-5, 5), 0, ThreadLocalRandom.current().nextDouble(-5, 5));
            loc.setY(loc.getWorld().getHighestBlockYAt(loc));
            Entity minion = loc.getWorld().spawnEntity(loc, type);
            if (minion instanceof Mob mob) {
                mob.setTarget(boss.getWorld().getNearestPlayer(loc, aggroRadius));
            }
        }
    }

    private void launchProjectile(LivingEntity boss, EntityType type, double speed) {
        Player target = boss.getWorld().getNearestPlayer(boss.getLocation(), 30);
        if (target == null) return;
        Projectile projectile = (Projectile) boss.getWorld().spawnEntity(boss.getEyeLocation().add(0, 0.2, 0), type);
        projectile.setShooter(boss);
        Vector direction = target.getEyeLocation().toVector().subtract(boss.getEyeLocation().toVector()).normalize().multiply(speed);
        projectile.setVelocity(direction);
    }

    private void createTemporaryBlocks(Location center) {
        Material material = Material.MAGMA_BLOCK;
        int ttlSeconds = plugin.getConfig().getInt("boss.behavior.temp-block-ttl-seconds", 20);
        for (int i = 0; i < 5; i++) {
            Location at = center.clone().add(ThreadLocalRandom.current().nextInt(-3, 4), -1, ThreadLocalRandom.current().nextInt(-3, 4));
            Block block = at.getBlock();
            if (!block.getType().isAir()) continue;
            block.setType(material, false);
            activeBoss.temporaryBlocks.add(block.getLocation());
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (activeBoss != null && activeBoss.temporaryBlocks.remove(block.getLocation())) {
                    if (block.getType() == material) block.setType(Material.AIR, false);
                }
            }, ttlSeconds * 20L);
        }
    }

    private void clearTemporaryBlocks() {
        if (activeBoss == null) return;
        for (Location location : activeBoss.temporaryBlocks) {
            if (location.getBlock().getType() == Material.MAGMA_BLOCK) {
                location.getBlock().setType(Material.AIR, false);
            }
        }
        activeBoss.temporaryBlocks.clear();
    }

    public void registerDamage(UUID playerId, double amount) {
        if (activeBoss == null) return;
        registerParticipant(playerId);
        activeBoss.damage.merge(playerId, amount, Double::sum);
    }

    public void registerParticipant(UUID playerId) {
        if (activeBoss != null) activeBoss.participants.add(playerId);
    }

    public void handleBossDeath() {
        if (activeBoss == null) return;
        rewardParticipants();
        clearRespawnLocks();
        publishDamageTop();
        removeBoss(false);
    }

    private void rewardParticipants() {
        BossType type = activeBoss.bossType;
        int minCoins = plugin.getConfig().getInt("bosses." + type.id() + ".reward-coins-min", 50);
        int maxCoins = plugin.getConfig().getInt("bosses." + type.id() + ".reward-coins-max", 50);
        int coins = ThreadLocalRandom.current().nextInt(minCoins, maxCoins + 1);
        for (UUID participant : activeBoss.participants) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "money add " + Bukkit.getOfflinePlayer(participant).getName() + " " + coins);
            pendingClaims.computeIfAbsent(participant, k -> new ArrayList<>()).addAll(buildLoot(activeBoss.bossType));
        }
    }

    private List<ItemStack> buildLoot(BossType type) {
        String basePath = "loot-tables." + type.id();
        ConfigurationSection table = plugin.getConfig().getConfigurationSection(basePath);
        if (table == null) {
            return buildDefaultLoot(type);
        }

        List<Map<?, ?>> rawEntries = table.getMapList("entries");
        if (rawEntries.isEmpty()) {
            return buildDefaultLoot(type);
        }

        int rolls = Math.max(1, table.getInt("rolls", 1));
        List<LootEntry> entries = new ArrayList<>();
        int totalWeight = 0;
        for (Map<?, ?> rawEntry : rawEntries) {
            LootEntry entry = parseLootEntry(rawEntry);
            if (entry == null) continue;
            entries.add(entry);
            totalWeight += entry.weight();
        }

        if (entries.isEmpty() || totalWeight <= 0) {
            return buildDefaultLoot(type);
        }

        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < rolls; i++) {
            LootEntry chosen = chooseLoot(entries, totalWeight);
            if (chosen == null) continue;
            int amount = ThreadLocalRandom.current().nextInt(chosen.minAmount(), chosen.maxAmount() + 1);
            ItemStack item = new ItemStack(chosen.material(), amount);
            applyMetadata(item, chosen);
            items.add(item);
        }
        return items;
    }

    private LootEntry parseLootEntry(Map<?, ?> rawEntry) {
        String materialName = String.valueOf(rawEntry.getOrDefault("material", "AIR")).toUpperCase(Locale.ROOT);
        Material material = Material.matchMaterial(materialName);
        if (material == null || material.isAir()) return null;

        int weight = Math.max(1, parseInt(rawEntry.get("weight"), 1));

        int min = 1;
        int max = 1;
        Object amountObj = rawEntry.get("amount");
        if (amountObj instanceof Map<?, ?> amountMap) {
            min = Math.max(1, parseInt(amountMap.get("min"), 1));
            max = Math.max(min, parseInt(amountMap.get("max"), min));
        }

        String name = null;
        List<String> lore = List.of();
        Object metadataObj = rawEntry.get("metadata");
        if (metadataObj instanceof Map<?, ?> metaMap) {
            Object rawName = metaMap.get("name");
            if (rawName != null) name = ChatColor.translateAlternateColorCodes('&', rawName.toString());
            Object rawLore = metaMap.get("lore");
            if (rawLore instanceof List<?> loreList) {
                List<String> translated = new ArrayList<>();
                for (Object line : loreList) {
                    translated.add(ChatColor.translateAlternateColorCodes('&', String.valueOf(line)));
                }
                lore = translated;
            }
        }

        Set<ItemFlag> flags = EnumSet.noneOf(ItemFlag.class);
        Object flagsObj = rawEntry.get("flags");
        if (flagsObj instanceof List<?> list) {
            for (Object flagObj : list) {
                try {
                    flags.add(ItemFlag.valueOf(String.valueOf(flagObj).toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        Map<String, String> unique = new HashMap<>();
        Object uniqueObj = rawEntry.get("unique");
        if (uniqueObj instanceof Map<?, ?> uniqueMap) {
            for (Map.Entry<?, ?> entry : uniqueMap.entrySet()) {
                unique.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }

        return new LootEntry(material, weight, min, max, name, lore, flags, unique);
    }

    private int parseInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private LootEntry chooseLoot(List<LootEntry> entries, int totalWeight) {
        int roll = ThreadLocalRandom.current().nextInt(totalWeight);
        int cursor = 0;
        for (LootEntry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) return entry;
        }
        return entries.get(entries.size() - 1);
    }

    private List<ItemStack> buildDefaultLoot(BossType type) {
        List<ItemStack> drops = new ArrayList<>();
        drops.add(new ItemStack(type.lootMaterial(), 1));

        switch (type) {
            case ZOMBIE_GIANT -> {
                drops.add(new ItemStack(Material.POTION, 3));
                drops.add(new ItemStack(Material.GOLDEN_APPLE, 3));
            }
            case IFRIT_TITAN -> {
                drops.add(new ItemStack(Material.BLAZE_ROD, ThreadLocalRandom.current().nextInt(15, 21)));
                drops.add(new ItemStack(Material.POTION, 1));
            }
            case GIANT_PHANTOM -> drops.add(new ItemStack(Material.PHANTOM_MEMBRANE, 2));
            case GIANT_SHULKER -> drops.add(new ItemStack(Material.SHULKER_SHELL, ThreadLocalRandom.current().nextInt(1, 4)));
            case FOREST_GUARDIAN -> {
                drops.add(new ItemStack(Material.EMERALD, ThreadLocalRandom.current().nextInt(30, 51)));
                drops.add(new ItemStack(Material.IRON_INGOT, ThreadLocalRandom.current().nextInt(20, 31)));
            }
            default -> {
            }
        }

        if (type == BossType.ZOMBIE_GIANT && ThreadLocalRandom.current().nextDouble() < 0.2) {
            ItemStack head = new ItemStack(Material.ZOMBIE_HEAD, 1);
            ItemMeta meta = head.getItemMeta();
            meta.displayName(Component.text("Голова зомби-гиганта", NamedTextColor.DARK_GREEN));
            head.setItemMeta(meta);
            drops.add(head);
        }

        if (type == BossType.GIANT_PHANTOM && ThreadLocalRandom.current().nextDouble() < 0.2) {
            drops.add(new ItemStack(Material.ELYTRA, 1));
        }

        if (type == BossType.GIANT_SHULKER) {
            ItemStack shard = new ItemStack(Material.AMETHYST_SHARD, 1);
            ItemMeta meta = shard.getItemMeta();
            meta.displayName(Component.text("Гравитационный осколок", NamedTextColor.LIGHT_PURPLE));
            shard.setItemMeta(meta);
            drops.add(shard);
        }

        return drops;
    }

    private void publishDamageTop() {

        List<Map.Entry<UUID, Double>> top = new ArrayList<>(activeBoss.damage.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        Bukkit.broadcast(Component.text("Топ урона по боссу:", NamedTextColor.AQUA));
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            Map.Entry<UUID, Double> row = top.get(i);
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(row.getKey()).getName()).orElse("Unknown");
            Bukkit.broadcast(Component.text((i + 1) + ". " + name + " — " + String.format(Locale.US, "%.1f", row.getValue()), NamedTextColor.WHITE));
        }
    }

    public String claim(Player player) {
        List<ItemStack> loot = pendingClaims.get(player.getUniqueId());
        if (loot == null || loot.isEmpty()) {
            return "У вас нет наград.";
        }
        if (player.getInventory().firstEmpty() == -1) {
            return "Инвентарь заполнен.";
        }

        HashMap<Integer, ItemStack> leftovers = player.getInventory().addItem(loot.toArray(new ItemStack[0]));
        if (!leftovers.isEmpty()) {
            return "Инвентарь заполнен.";
        }

        loot.clear();
        return "Награда выдана.";
    }

    public void lockRespawn(Player player) {
        long until = System.currentTimeMillis() + plugin.getConfig().getLong("combat.respawn-lock-minutes", 10L) * 60_000L;
        respawnBlockedUntil.put(player.getUniqueId(), until);
    }

    public void unlockRespawn(UUID playerId) {
        respawnBlockedUntil.remove(playerId);
    }

    public World getRespawnLockWaitingWorld() {
        FileConfiguration config = plugin.getConfig();
        String worldName = config.getString("combat.respawn-lock.waiting-world", "world");
        return Bukkit.getWorld(worldName);
    }

    public boolean shouldKickIfWaitingWorldMissing() {
        return plugin.getConfig().getBoolean("combat.respawn-lock.kick-if-waiting-world-missing", true);
    }

    public String getRespawnLockKickMessage(UUID playerId) {
        long left = getRespawnBlockSecondsLeft(playerId);
        return "Выбывание временно заблокировано. До разблокировки осталось " + left + " сек.";
    }

    public boolean isRespawnBlocked(UUID playerId) {
        return respawnBlockedUntil.getOrDefault(playerId, 0L) > System.currentTimeMillis();
    }

    public long getRespawnBlockSecondsLeft(UUID playerId) {
        return Math.max(0L, (respawnBlockedUntil.getOrDefault(playerId, 0L) - System.currentTimeMillis()) / 1000L);
    }

    public void clearRespawnLocks() {
        respawnBlockedUntil.clear();
    }

    private BossType nextBossType() {
        String configured = plugin.getConfig().getString("bosses.next-type", "zombie_giant");
        return BossType.fromId(configured);
    }

    private boolean isSpawnDayAllowed() {
        LocalDate today = LocalDate.now();
        DayOfWeek other = DayOfWeek.valueOf(plugin.getConfig().getString("schedule.second-day", "WEDNESDAY").toUpperCase(Locale.ROOT));
        return today.getDayOfWeek() == DayOfWeek.SATURDAY || today.getDayOfWeek() == other;
    }

    private void rotateWeekCounter() {
        int currentWeek = LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear());
        if (weekIndex != currentWeek) {
            weekIndex = currentWeek;
            spawnsThisWeek = 0;
            skippedDays.clear();
        }
    }

    private Location findSpawnPoint(World world, BossType type) {
        int min = plugin.getConfig().getInt("spawn.min-distance", 1000);
        int max = plugin.getConfig().getInt("spawn.max-distance", 8000);
        int attempts = plugin.getConfig().getInt("spawn.max-attempts", 120);
        int lenientAfter = plugin.getConfig().getInt("spawn.lenient-after", 80);
        Set<Biome> allowedBiomes = parseAllowedBiomes(type);

        Location spawn = world.getSpawnLocation();
        for (int i = 0; i < attempts; i++) {
            double angle = ThreadLocalRandom.current().nextDouble(0, Math.PI * 2);
            double distance = ThreadLocalRandom.current().nextDouble(min, max);
            int x = (int) Math.round(spawn.getX() + Math.cos(angle) * distance);
            int z = (int) Math.round(spawn.getZ() + Math.sin(angle) * distance);
            int y = world.getHighestBlockYAt(x, z);
            Location loc = new Location(world, x + 0.5, y + 1, z + 0.5);

            if (!allowedBiomes.contains(world.getBiome(x, y, z))) continue;
            if (!isOpenSky(loc)) continue;
            int manmade = countManmadeBlocks(loc, 126, i >= lenientAfter ? 20 : 10);
            if (manmade > (i >= lenientAfter ? 20 : 10)) continue;
            return loc;
        }
        return null;
    }

    private Set<Biome> parseAllowedBiomes(BossType type) {
        List<String> rules = plugin.getConfig().getStringList("bosses." + type.id() + ".allowed-biomes");
        if (rules.isEmpty()) rules = type.defaultBiomes();

        Set<Biome> allowed = EnumSet.noneOf(Biome.class);
        for (String rule : rules) {
            String token = rule.toUpperCase(Locale.ROOT);
            boolean deny = token.startsWith("!");
            token = deny ? token.substring(1) : token;
            boolean wildcardSuffix = token.startsWith("*");
            String criteria = wildcardSuffix ? token.substring(1) : token;

            for (Biome biome : Biome.values()) {
                String name = biome.name();
                boolean match = wildcardSuffix ? name.endsWith(criteria) : name.equals(criteria);
                if (match) {
                    if (deny) allowed.remove(biome);
                    else allowed.add(biome);
                }
            }
        }
        if (allowed.isEmpty()) {
            allowed.addAll(Arrays.asList(Biome.values()));
        }
        return allowed;
    }

    private boolean isOpenSky(Location loc) {
        return loc.getBlock().getLightFromSky() > 0;
    }

    private int countManmadeBlocks(Location center, int radius, int stopAt) {
        int count = 0;
        int step = 6;
        World world = center.getWorld();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();

        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int y = Math.max(world.getMinHeight(), cy - 24); y <= Math.min(world.getMaxHeight(), cy + 24); y += step) {
                for (int z = cz - radius; z <= cz + radius; z += step) {
                    Material m = world.getBlockAt(x, y, z).getType();
                    if (isManmade(m)) {
                        count++;
                        if (count > stopAt) return count;
                    }
                }
            }
        }
        return count;
    }

    private boolean isManmade(Material material) {
        if (material.isAir() || material == Material.WATER || material == Material.LAVA) return false;
        String n = material.name();
        if (n.contains("CHEST") || n.contains("BED") || n.contains("FURNACE") || n.contains("CRAFTING_TABLE")) return true;
        if (n.contains("PLANKS") || n.contains("BRICKS") || n.contains("CONCRETE") || n.contains("GLASS") || n.contains("WOOL")) return true;
        return material.isInteractable();
    }

    private record LootEntry(Material material, int weight, int minAmount, int maxAmount, String displayName,
                             List<String> lore, Set<ItemFlag> flags, Map<String, String> unique) {
    }

    public void saveState() {
        File file = new File(plugin.getDataFolder(), "boss-state.yml");
        YamlConfiguration state = new YamlConfiguration();

        state.set("week-index", weekIndex);
        state.set("spawns-this-week", spawnsThisWeek);
        List<String> skip = skippedDays.stream().map(Enum::name).toList();
        state.set("skipped-days", skip);
        state.set("respawn-locks", respawnBlockedUntil);

        ConfigurationSection claimsSection = state.createSection("pending-claims");
        for (Map.Entry<UUID, List<ItemStack>> entry : pendingClaims.entrySet()) {
            claimsSection.set(entry.getKey().toString(), entry.getValue());
        }

        if (activeBoss != null) {
            state.set("active.entity-id", activeBoss.entityId.toString());
            state.set("active.type", activeBoss.bossType.id());
            state.set("active.spawn", activeBoss.spawnLocation);
            state.set("active.spawned-at", activeBoss.spawnedAt);
            state.set("active.despawn-at", activeBoss.despawnAt);
            state.set("active.participants", activeBoss.participants.stream().map(UUID::toString).toList());
            Map<String, Double> damage = new HashMap<>();
            activeBoss.damage.forEach((k, v) -> damage.put(k.toString(), v));
            state.set("active.damage", damage);
        }

        try {
            state.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save boss-state.yml: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void loadState() {
        File file = new File(plugin.getDataFolder(), "boss-state.yml");
        if (!file.exists()) return;

        YamlConfiguration state = YamlConfiguration.loadConfiguration(file);
        weekIndex = state.getInt("week-index", -1);
        spawnsThisWeek = state.getInt("spawns-this-week", 0);

        skippedDays.clear();
        for (String day : state.getStringList("skipped-days")) {
            try {
                skippedDays.add(DayOfWeek.valueOf(day));
            } catch (IllegalArgumentException ignored) {
            }
        }

        respawnBlockedUntil.clear();
        ConfigurationSection lockSection = state.getConfigurationSection("respawn-locks");
        if (lockSection != null) {
            for (String key : lockSection.getKeys(false)) {
                respawnBlockedUntil.put(UUID.fromString(key), lockSection.getLong(key));
            }
        }

        pendingClaims.clear();
        ConfigurationSection claimsSection = state.getConfigurationSection("pending-claims");
        if (claimsSection != null) {
            for (String key : claimsSection.getKeys(false)) {
                List<ItemStack> items = (List<ItemStack>) claimsSection.getList(key, new ArrayList<>());
                pendingClaims.put(UUID.fromString(key), items);
            }
        }

        if (state.contains("active.entity-id")) {
            try {
                ActiveBoss restored = new ActiveBoss();
                restored.entityId = UUID.fromString(state.getString("active.entity-id"));
                restored.bossType = BossType.fromId(state.getString("active.type", "zombie_giant"));
                restored.spawnLocation = state.getLocation("active.spawn");
                restored.spawnedAt = state.getLong("active.spawned-at", System.currentTimeMillis());
                restored.despawnAt = state.getLong("active.despawn-at", System.currentTimeMillis());
                for (String participant : state.getStringList("active.participants")) {
                    restored.participants.add(UUID.fromString(participant));
                }
                ConfigurationSection damage = state.getConfigurationSection("active.damage");
                if (damage != null) {
                    for (String key : damage.getKeys(false)) {
                        restored.damage.put(UUID.fromString(key), damage.getDouble(key));
                    }
                }
                if (restored.spawnLocation == null || restored.despawnAt <= System.currentTimeMillis()) {
                    plugin.getLogger().warning("Boss state invalid or expired, removing.");
                    removeBoss(false);
                } else {
                    activeBoss = restored;
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to restore boss state, deleting active boss data: " + ex.getMessage());
                activeBoss = null;
            }
        }
    }

    public String info(CommandSender sender) {
        StringBuilder out = new StringBuilder();
        out.append("Следующий босс: ").append(nextBossType().id());
        if (sender.hasPermission("worldboss.admin") && activeBoss != null) {
            LivingEntity entity = getBossEntity();
            out.append(" | active=").append(activeBoss.bossType.id())
                    .append(" @ ").append(activeBoss.spawnLocation.getBlockX()).append(" ")
                    .append(activeBoss.spawnLocation.getBlockY()).append(" ")
                    .append(activeBoss.spawnLocation.getBlockZ())
                    .append(" | hp=").append(entity != null ? String.format(Locale.US, "%.1f", entity.getHealth()) : "N/A")
                    .append(" | secLeft=").append(Math.max(0, (activeBoss.despawnAt - System.currentTimeMillis()) / 1000));
        }
        return out.toString();
    }
}
