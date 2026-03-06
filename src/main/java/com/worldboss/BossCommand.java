package com.worldboss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class BossCommand implements CommandExecutor, TabCompleter {
    private final WorldBossPlugin plugin;
    private final BossManager manager;

    public BossCommand(WorldBossPlugin plugin, BossManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("/boss ready|exit|claim|info|skip|spawn|respawn", NamedTextColor.YELLOW));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "ready" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Только для игроков.");
                    return true;
                }
                boolean ok = manager.setReady(player);
                if (!ok) {
                    player.sendMessage(Component.text("Сейчас нельзя запустить событие.", NamedTextColor.RED));
                    return true;
                }
                plugin.getServer().broadcast(Component.text(player.getName() + " готов к боссу (" + manager.getReadyCount() + ")", NamedTextColor.GREEN));
                manager.saveState();
            }
            case "exit" -> {
                if (sender instanceof Player player) {
                    manager.setNotReady(player);
                    sender.sendMessage(Component.text("Вы больше не в списке готовых.", NamedTextColor.GRAY));
                    manager.saveState();
                }
            }
            case "claim" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Только для игроков.");
                    return true;
                }
                sender.sendMessage(Component.text(manager.claim(player), NamedTextColor.GOLD));
                manager.saveState();
            }
            case "skip" -> {
                if (!ensureAdmin(sender)) return true;
                manager.skipToday();
                sender.sendMessage(Component.text("Сегодняшний босс пропущен.", NamedTextColor.GRAY));
                manager.saveState();
            }
            case "spawn" -> {
                if (!ensureAdmin(sender)) return true;
                boolean started = manager.startEvent(true);
                sender.sendMessage(Component.text(started ? "Событие запущено." : "Не удалось запустить событие.", started ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "respawn" -> {
                if (!ensureAdmin(sender)) return true;
                boolean ok = manager.respawnBoss();
                sender.sendMessage(Component.text(ok ? "Босс пересоздан." : "Нет активного босса.", ok ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
            case "info" -> sender.sendMessage(Component.text(manager.info(sender), NamedTextColor.AQUA));
            default -> sender.sendMessage(Component.text("Неизвестная подкоманда.", NamedTextColor.RED));
        }

        return true;
    }

    private boolean ensureAdmin(CommandSender sender) {
        if (!sender.hasPermission("worldboss.admin")) {
            sender.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED));
            return false;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("ready", "exit", "claim", "info"));
            if (sender.hasPermission("worldboss.admin")) options.addAll(List.of("skip", "spawn", "respawn"));
            return options.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
