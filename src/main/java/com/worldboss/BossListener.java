package com.worldboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

public class BossListener implements Listener {
    private final WorldBossPlugin plugin;
    private final BossManager manager;

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
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (manager.getActiveBoss() == null) return;
        Player player = event.getEntity();
        if (!manager.getActiveBoss().participants.contains(player.getUniqueId())) return;

        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.lockRespawn(player);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!manager.isRespawnBlocked(player.getUniqueId())) return;

        long left = manager.getRespawnBlockSecondsLeft(player.getUniqueId());
        player.sendMessage(Component.text("Возрождение ограничено на " + left + " секунд.", NamedTextColor.RED));
        player.setGameMode(GameMode.SPECTATOR);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (!manager.isRespawnBlocked(player.getUniqueId())) {
                player.setGameMode(GameMode.SURVIVAL);
                player.sendMessage(Component.text("Вы снова можете участвовать в бою.", NamedTextColor.GREEN));
            }
        }, Math.max(20L, left * 20L));
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
