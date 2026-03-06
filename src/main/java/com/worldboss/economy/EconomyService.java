package com.worldboss.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public class EconomyService {
    private final EconomyRepository repository;

    public EconomyService(EconomyRepository repository) {
        this.repository = repository;
    }

    public void initialize() throws SQLException {
        repository.initialize();
    }

    public long getBalance(Player player) throws SQLException {
        return repository.getBalance(player.getUniqueId(), player.getName());
    }

    public long getBalance(OfflinePlayer player) throws SQLException {
        return repository.getBalance(player.getUniqueId(), resolveName(player));
    }

    public Optional<EconomyAccount> findByName(String name) throws SQLException {
        return repository.findByName(name);
    }

    public void addBalance(UUID playerId, String playerName, long amount) throws SQLException {
        repository.addBalance(playerId, playerName, amount);
    }

    public boolean transfer(Player from, EconomyAccount to, long amount) throws SQLException {
        return repository.transfer(from.getUniqueId(), from.getName(), to.playerId(), to.playerName(), amount);
    }

    public void close() {
        repository.close();
    }

    private String resolveName(OfflinePlayer player) {
        return player.getName() != null ? player.getName() : player.getUniqueId().toString().substring(0, 8);
    }
}
