package ru.dtpsaa.sinusac.config;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {

    private FileConfiguration cfg;

    public PluginConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public void reload(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    public String getLicenseKey() {
        return this.cfg.getString("license-key", "").trim();
    }

    public String getServerUrl() {
        return this.cfg.getString("server.url", "https://sinusai.tech");
    }

    public String getLocale() {
        String locale = this.cfg.getString("locale", "en").trim().toLowerCase();
        return locale.equals("ru") ? "ru" : "en";
    }

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

    private String resolve(String checkType) {
        return (checkType.equals("killaura") || checkType.equals("aimassist")) ? "combat" : checkType;
    }

    public boolean isCheckEnabled(String checkType) {
        return this.cfg.getBoolean("checks." + resolve(checkType) + ".enabled", true);
    }

    public int getCombatRequestIntervalTicks() {
        return Math.max(5, Math.min(100,
                this.cfg.getInt("checks.combat.request-interval-ticks", 20)));
    }

    public double getAutoFlagThreshold(String checkType) {
        return this.cfg.getDouble("checks." + resolve(checkType) + ".auto-flag-threshold", 0.85D);
    }

    public double getNotifyThreshold(String checkType) {
        return this.cfg.getDouble("checks." + resolve(checkType) + ".notify-threshold", 0.7D);
    }

    public double getAlertThreshold(String checkType) {
        return this.cfg.getDouble("checks." + resolve(checkType) + ".alert-threshold", 0.0D);
    }

    public boolean isFlyBedrockOnly() {
        return this.cfg.getBoolean("checks.fly.bedrock-only", true);
    }

    public int getFlyBatchSize() {
        return Math.max(5, Math.min(60, this.cfg.getInt("checks.fly.batch-size", 20)));
    }

    public int getFlyMinVl() {
        return Math.max(1, Math.min(20, this.cfg.getInt("checks.fly.min-vl", 3)));
    }

    public int getFlyMaxMvl() {
        return Math.max(1, Math.min(1000, this.cfg.getInt("checks.fly.max-mvl", 12)));
    }

    public boolean isFlySetback() {
        return this.cfg.getBoolean("checks.fly.setback", true);
    }

    public List<String> getFlyPunishCommands() {
        return this.cfg.getStringList("checks.fly.punish-commands");
    }

    public int getMaxVl() {
        return this.cfg.getInt("collection.max-vl", 6);
    }

    public List<String> getPunishCommands() {
        return this.cfg.getStringList("collection.punish-commands");
    }

}
