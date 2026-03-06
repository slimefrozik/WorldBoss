package com.worldboss;

import com.worldboss.economy.DatabaseSettings;
import com.worldboss.economy.EconomyService;
import com.worldboss.economy.MoneyCommand;
import com.worldboss.economy.SqlEconomyRepository;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;

public final class WorldBossPlugin extends JavaPlugin {
    private BossManager bossManager;
    private EconomyService economyService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        DatabaseSettings settings = DatabaseSettings.fromConfig(getConfig().getConfigurationSection("database"));
        economyService = new EconomyService(new SqlEconomyRepository(this, settings));
        try {
            economyService.initialize();
        } catch (SQLException e) {
            getLogger().severe("Cannot initialize economy storage: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        bossManager = new BossManager(this, economyService);
        bossManager.loadState();

        BossCommand command = new BossCommand(this, bossManager);
        getCommand("boss").setExecutor(command);
        getCommand("boss").setTabCompleter(command);

        MoneyCommand moneyCommand = new MoneyCommand(economyService);
        getCommand("money").setExecutor(moneyCommand);
        getCommand("money").setTabCompleter(moneyCommand);
        getCommand("balance").setExecutor(moneyCommand);
        getCommand("balance").setTabCompleter(moneyCommand);

        getServer().getPluginManager().registerEvents(new BossListener(this, bossManager), this);

        bossManager.startTickTasks();
        getLogger().info("WorldBoss enabled");
    }

    @Override
    public void onDisable() {
        if (bossManager != null) {
            bossManager.shutdown();
            bossManager.saveState();
        }
        if (economyService != null) {
            economyService.close();
        }
    }

    public BossManager getBossManager() {
        return bossManager;
    }
}
