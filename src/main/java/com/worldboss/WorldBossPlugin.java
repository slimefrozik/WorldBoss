package com.worldboss;

import org.bukkit.plugin.java.JavaPlugin;

public final class WorldBossPlugin extends JavaPlugin {
    private BossManager bossManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        bossManager = new BossManager(this);
        bossManager.loadState();

        BossCommand command = new BossCommand(this, bossManager);
        getCommand("boss").setExecutor(command);
        getCommand("boss").setTabCompleter(command);
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
    }

    public BossManager getBossManager() {
        return bossManager;
    }
}
