package ru.dtpsaa.sinusac;

import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dtpsaa.sinusac.collector.CombatListener;
import ru.dtpsaa.sinusac.collector.MovementListener;
import ru.dtpsaa.sinusac.collector.SessionManager;
import ru.dtpsaa.sinusac.command.CommandRegistry;
import ru.dtpsaa.sinusac.config.Messages;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.util.ApiClient;
import ru.dtpsaa.sinusac.util.HologramManager;

/**
 * SinusAC — пользовательская часть античита SinusAI.
 * <p>
 * Главный класс отвечает ТОЛЬКО за жизненный цикл плагина:
 * загрузка конфигов, проверка лицензии, инициализация менеджеров,
 * регистрация листенеров и команд. Никакой командной логики здесь нет —
 * она разбита по пакетам command/<имя>/<Имя>Command.java.
 * <p>
 * Функции обучения (train/learn) вынесены в отдельный плагин SinusOP,
 * который подключается к этому плагину через {@link #getInstance()}
 * и публичные геттеры ниже (depend: [SinusAC] в plugin.yml SinusOP).
 */
public final class SinusAC extends JavaPlugin {

    private static SinusAC instance;

    private PluginConfig pluginConfig;
    private ApiClient apiClient;
    private SessionManager sessionManager;
    private Messages msgs;
    private HologramManager holoManager;

    /** Глобальный тумблер алертов (рассылка всем с правом sinusac.alerts). */
    private volatile boolean alertsEnabled = true;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        saveResource("messages.yml", false);

        this.msgs = new Messages(this);
        this.pluginConfig = new PluginConfig(getConfig());

        // ---------- API-клиент ----------
        try {
            this.apiClient = new ApiClient(this.pluginConfig.getServerUrl(), this.pluginConfig.getLicenseKey());
            this.apiClient.updateConfig(this.pluginConfig);
        } catch (Exception e) {
            getLogger().severe("Ошибка инициализации API: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        printBanner();

        // ---------- Лицензия ----------
        String key = this.pluginConfig.getLicenseKey();
        if (key == null || key.isEmpty() || !key.startsWith("SINUSAI-")) {
            getLogger().severe("Укажите лицензионный ключ в config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("Проверка лицензии...");
        try {
            ApiClient.LicenseResult lic = this.apiClient.validateLicense();
            if (!lic.valid) {
                getLogger().severe("Лицензия недействительна: " + lic.message);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            getLogger().info("Лицензия принята: " + lic.message);
        } catch (Exception e) {
            getLogger().severe("Ошибка проверки лицензии: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // ---------- Менеджеры, листенеры, команды ----------
        try {
            this.sessionManager = new SessionManager(this, this.apiClient, this.pluginConfig);
            this.holoManager = new HologramManager(this);

            getServer().getPluginManager().registerEvents((Listener) new MovementListener(this.sessionManager), (Plugin) this);
            getServer().getPluginManager().registerEvents((Listener) new CombatListener(this.sessionManager), (Plugin) this);

            // Регистрация всех подкоманд /sinusac — см. command/CommandRegistry
            new CommandRegistry(this).register("sinusac");

            getLogger().info("SinusAC успешно запущен.");
        } catch (Exception e) {
            getLogger().severe("Ошибка запуска: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (this.sessionManager != null)
            this.sessionManager.flushAll();
        if (this.holoManager != null)
            this.holoManager.removeAll();
        getLogger().info("SinusAC отключён.");
    }

    private void printBanner() {
        getLogger().info("  ____  _                       _    ____ ");
        getLogger().info(" / ___|(_)_ __  _   _ ___      / \\  / ___|");
        getLogger().info(" \\___ \\| | '_ \\| | | / __|    / _ \\| |    ");
        getLogger().info("  ___) | | | | | |_| \\__ \\   / ___ \\ |___ ");
        getLogger().info(" |____/|_|_| |_|\\__,_|___/  /_/   \\_\\____|");
        getLogger().info("  SinusAC (powered by SinusAI) v1.0.0");
    }

    // ==================== Публичный API (используется и SinusOP) ====================

    public static SinusAC getInstance()          { return instance; }
    public Messages getMessages()                { return this.msgs; }
    public PluginConfig getPluginConfig()        { return this.pluginConfig; }
    public ApiClient getApiClient()              { return this.apiClient; }
    public SessionManager getSessionManager()    { return this.sessionManager; }
    public HologramManager getHoloManager()      { return this.holoManager; }

    public boolean isAlertsEnabled()             { return this.alertsEnabled; }
    public void setAlertsEnabled(boolean v)      { this.alertsEnabled = v; }
}
