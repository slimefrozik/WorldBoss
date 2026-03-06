package com.worldboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class BossListener implements Listener {
    private final WorldBossPlugin plugin;
    private final BossManager manager;
    private final Map<UUID, BukkitTask> lockTasks = new HashMap<>();

    public BossListener(WorldBossPlugin plugin, BossManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onBossDamaged(EntityDamageByEntityEvent event) {
        ActiveBoss active = manager.getActiveBoss();
        if (active == null) return;
        if (!event.getEntity().getUniqueId().equals(active.entityId)) return;

        UUID playerId = resolvePlayerDamager(event.getDamager());
        if (playerId != null) {
            manager.registerDamage(playerId, event.getFinalDamage());
        }
    }

    @EventHandler
    public void onPlayerDamagedByBoss(EntityDamageByEntityEvent event) {
        ActiveBoss active = manager.getActiveBoss();
        if (active == null) return;
        if (!(event.getEntity() instanceof Player player)) return;

        Entity damager = event.getDamager();
        if (damager.getUniqueId().equals(active.entityId)) {
            manager.registerParticipant(player.getUniqueId());
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        ActiveBoss active = manager.getActiveBoss();
        if (active == null) return;
        if (!event.getEntity().getUniqueId().equals(active.entityId)) return;

        event.getDrops().clear();
        manager.handleBossDeath();
        releaseAllBlockedPlayers();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (manager.getActiveBoss() == null) return;
        Player player = event.getEntity();
        if (!manager.getActiveBoss().participants.contains(player.getUniqueId())) return;

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.lockRespawn(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!manager.isRespawnBlocked(playerId)) return;

        World waitingWorld = manager.getRespawnLockWaitingWorld();
        if (waitingWorld == null) {
            if (manager.shouldKickIfWaitingWorldMissing()) {
                Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(manager.getRespawnLockKickMessage(playerId), NamedTextColor.RED)));
            } else {
                player.sendMessage(Component.text("Мир ожидания не найден, блокировка респавна остается активной.", NamedTextColor.RED));
            }
            return;
        }

        Location waitingSpawn = waitingWorld.getSpawnLocation();
        event.setRespawnLocation(waitingSpawn);
        player.sendMessage(Component.text("Вы отправлены в мир ожидания до снятия блокировки.", NamedTextColor.RED));
        player.sendMessage(Component.text("Осталось секунд: " + manager.getRespawnBlockSecondsLeft(playerId), NamedTextColor.GOLD));
        player.setGameMode(GameMode.SPECTATOR);
        scheduleReleaseCheck(player);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        if (!manager.isRespawnBlocked(playerId)) {
            cancelReleaseTask(playerId);
            return;
        }

        World waitingWorld = manager.getRespawnLockWaitingWorld();
        if (waitingWorld != null && !player.getWorld().equals(waitingWorld)) {
            player.teleport(waitingWorld.getSpawnLocation());
        }
        player.setGameMode(GameMode.SPECTATOR);
        player.sendMessage(Component.text("Возрождение ограничено. Осталось секунд: " + manager.getRespawnBlockSecondsLeft(playerId), NamedTextColor.RED));
        scheduleReleaseCheck(player);
    }

    private void scheduleReleaseCheck(Player player) {
        UUID playerId = player.getUniqueId();
        cancelReleaseTask(playerId);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) return;
            if (manager.isRespawnBlocked(playerId)) {
                return;
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.sendMessage(Component.text("Блокировка снята. Вы можете вернуться в бой.", NamedTextColor.GREEN));
            cancelReleaseTask(playerId);
        }, 20L, 20L);
        lockTasks.put(playerId, task);
    }

    private void releaseAllBlockedPlayers() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID playerId = online.getUniqueId();
            if (manager.isRespawnBlocked(playerId)) {
                manager.unlockRespawn(playerId);
            }
            if (online.getGameMode() == GameMode.SPECTATOR) {
                online.setGameMode(GameMode.SURVIVAL);
                online.sendMessage(Component.text("Босс повержен, ограничения респавна сняты досрочно.", NamedTextColor.GREEN));
            }
            cancelReleaseTask(playerId);
        }
    }

    private void cancelReleaseTask(UUID playerId) {
        BukkitTask existing = lockTasks.remove(playerId);
        if (existing != null) {
            existing.cancel();
        }
    }

    private UUID resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player.getUniqueId();
            }
        }
        if (damager instanceof Tameable tameable && tameable.getOwner() instanceof Player owner) {
            return owner.getUniqueId();
        }
        return null;
    }
}
