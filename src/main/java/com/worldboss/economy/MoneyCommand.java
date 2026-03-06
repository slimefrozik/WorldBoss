package com.worldboss.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.List;

public class MoneyCommand implements CommandExecutor, TabCompleter {
    private final EconomyService economyService;

    public MoneyCommand(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Команда только для игроков.", NamedTextColor.RED));
            return true;
        }

        try {
            if (args.length == 0) {
                showBalance(player);
                return true;
            }

            if ("send".equalsIgnoreCase(args[0])) {
                handleSend(player, args);
                return true;
            }

            sender.sendMessage(Component.text("Использование: /money [send <nick> <count>]", NamedTextColor.YELLOW));
            return true;
        } catch (SQLException e) {
            player.sendMessage(Component.text("Ошибка экономики. Обратитесь к администратору.", NamedTextColor.RED));
            return true;
        }
    }

    private void showBalance(Player player) throws SQLException {
        long balance = economyService.getBalance(player);
        player.sendMessage(Component.text("Ваш баланс: " + balance, NamedTextColor.GOLD));
    }

    private void handleSend(Player from, String[] args) throws SQLException {
        if (args.length < 3) {
            from.sendMessage(Component.text("Использование: /money send <nick> <count>", NamedTextColor.YELLOW));
            return;
        }

        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            from.sendMessage(Component.text("Сумма должна быть целым числом.", NamedTextColor.RED));
            return;
        }

        if (amount <= 0) {
            from.sendMessage(Component.text("Сумма должна быть больше 0.", NamedTextColor.RED));
            return;
        }

        EconomyAccount target = economyService.findByName(args[1]).orElseGet(() -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(args[1]);
            if (player.getUniqueId() == null) {
                return null;
            }
            String name = player.getName() != null ? player.getName() : args[1];
            return new EconomyAccount(player.getUniqueId(), name, 0);
        });
        if (target == null) {
            from.sendMessage(Component.text("Игрок не найден.", NamedTextColor.RED));
            return;
        }

        if (target.playerId().equals(from.getUniqueId())) {
            from.sendMessage(Component.text("Нельзя переводить самому себе.", NamedTextColor.RED));
            return;
        }

        boolean ok = economyService.transfer(from, target, amount);
        if (!ok) {
            from.sendMessage(Component.text("Недостаточно средств.", NamedTextColor.RED));
            return;
        }

        from.sendMessage(Component.text("Перевод выполнен: " + amount + " -> " + target.playerName(), NamedTextColor.GREEN));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("send").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
