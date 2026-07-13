package ru.dtpsaa.sinusac.config;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Типизированная обёртка над config.yml.
 * Один экземпляр живёт весь аптайм плагина; при /sinusac reload
 * ему передаётся свежий FileConfiguration через reload().
 */
public class PluginConfig {

    private FileConfiguration cfg;

    public PluginConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void reload(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    // ---------- Лицензия и API ----------

    public String getLicenseKey() {
        return this.cfg.getString("license-key", "").trim();
    }

    public String getServerUrl() {
        return this.cfg.getString("server.url", "https://sinusai.tech");
    }

    public boolean isAsync() {
        return this.cfg.getBoolean("server.async", true);
    }

    // ---------- Сбор фреймов ----------

    public int getMinFrames() {
        return this.cfg.getInt("collection.min-frames", 30);
    }

    public int getMaxFrames() {
        return this.cfg.getInt("collection.max-frames", 200);
    }

    public int getCombatTimeoutTicks() {
        return this.cfg.getInt("collection.combat-timeout-ticks", 40);
    }

    public boolean isSendOnCombatEnd() {
        return this.cfg.getBoolean("collection.send-on-combat-end", true);
    }

    public int getGcdHistorySize() {
        return this.cfg.getInt("gcd.history-size", 40);
    }

    // ---------- Пороги проверок ----------

    /** killaura/aimassist схлопываются в общий combat-чек. */
    private String resolve(String checkType) {
        return (checkType.equals("killaura") || checkType.equals("aimassist")) ? "combat" : checkType;
    }

    public double getAutoFlagThreshold(String checkType) {
        return this.cfg.getDouble("checks." + resolve(checkType) + ".auto-flag-threshold", 0.85D);
    }

    public double getNotifyThreshold(String checkType) {
        return this.cfg.getDouble("checks." + resolve(checkType) + ".notify-threshold", 0.7D);
    }

    public double getAlertThreshold(String checkType) {
        return this.cfg.getDouble("checks." + checkType + ".alert-threshold", 0.0D);
    }

    // ---------- Наказания ----------

    public int getMaxVl() {
        return this.cfg.getInt("collection.max-vl", 6);
    }

    public List<String> getPunishCommands() {
        return this.cfg.getStringList("collection.punish-commands");
    }

    // ---------- Сообщения ----------

    public String getNotifyMessage() {
        return this.cfg.getString("actions.notify.message",
                "&8[&bSinusAI&8] &e{player} &8[{platform}] &7| {verdict} &7| {prob}% | {conf}%");
    }
}
