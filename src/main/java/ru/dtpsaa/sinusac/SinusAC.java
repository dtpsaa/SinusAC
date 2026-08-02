package ru.dtpsaa.sinusac;

import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import ru.dtpsaa.sinusac.collector.CombatListener;
import ru.dtpsaa.sinusac.collector.FlyManager;
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
public class SinusAC extends JavaPlugin {

    private static SinusAC instance;

    private PluginConfig pluginConfig;
    private ApiClient apiClient;
    private SessionManager sessionManager;
    private FlyManager flyManager;
    private Messages msgs;
    private HologramManager holoManager;
    private volatile boolean stopping;
    private volatile boolean ready;

    /** Глобальный тумблер алертов (рассылка всем с правом sinusac.alerts). */
    private volatile boolean alertsEnabled = true;

    @Override
    public void onEnable() {
        instance = this;
        this.stopping = false;
        this.ready = false;
        saveDefaultConfig();
        // Merge newly introduced options into existing installations without
        // overwriting the license key or server-specific values.
        getConfig().options().copyDefaults(true);
        // 1.1.0 derives public-ip:bukkit-port automatically and uses true
        // HttpClient async calls, so these legacy options are obsolete.
        getConfig().set("server.server-id", null);
        getConfig().set("server.async", null);
        getConfig().set("actions", null);
        saveConfig();

        this.pluginConfig = new PluginConfig(getConfig());
        this.msgs = new Messages(this);

        // ---------- API-клиент ----------
        try {
            this.apiClient = new ApiClient(this.pluginConfig.getServerUrl(), this.pluginConfig.getLicenseKey());
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
        // Create the public API immediately so dependent plugins can attach to
        // SinusAC, but keep all checks idle until the async license check passes.
        if (!initializeManagers())
            return;

        // Public IP discovery and license validation are network operations.
        // Never run them on the Minecraft tick thread.
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            ApiClient.LicenseResult result;
            try {
                this.apiClient.updateConfig(this.pluginConfig, getServer().getPort());
                result = this.apiClient.validateLicense();
            } catch (Exception e) {
                result = ApiClient.LicenseResult.fail("Ошибка проверки лицензии: " + e.getMessage());
            }
            ApiClient.LicenseResult completed = result;
            runOnMainThread(() -> finishEnable(completed));
        });
    }

    private void finishEnable(ApiClient.LicenseResult lic) {
        if (this.stopping || !isEnabled())
            return;
        if (!lic.valid) {
            getLogger().severe("Лицензия недействительна: " + lic.message);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("Лицензия принята: " + lic.message);
        this.ready = true;
        getLogger().info("SinusAC успешно запущен.");
    }

    private boolean initializeManagers() {
        // ---------- Менеджеры, листенеры, команды ----------
        try {
            this.sessionManager = new SessionManager(this, this.apiClient, this.pluginConfig);
            this.holoManager = new HologramManager(this);
            this.flyManager = new FlyManager(this, this.apiClient, this.sessionManager, this.pluginConfig);

            getServer().getPluginManager().registerEvents((Listener) new MovementListener(this.sessionManager), (Plugin) this);
            getServer().getPluginManager().registerEvents((Listener) new CombatListener(this.sessionManager), (Plugin) this);
            getServer().getPluginManager().registerEvents((Listener) this.flyManager, (Plugin) this);

            // Регистрация всех подкоманд /sinusac — см. command/CommandRegistry
            new CommandRegistry(this).register("sinusac");
            return true;
        } catch (Exception e) {
            getLogger().severe("Ошибка запуска: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    @Override
    public void onDisable() {
        this.stopping = true;
        this.ready = false;
        if (this.flyManager != null)
            this.flyManager.shutdown();
        if (this.sessionManager != null)
            this.sessionManager.flushAll();
        if (this.holoManager != null)
            this.holoManager.removeAll();
        if (instance == this)
            instance = null;
        getLogger().info("SinusAC отключён.");
    }

    /** Safely hands an async result back to Bukkit, ignoring late callbacks after disable/reload. */
    public boolean runOnMainThread(Runnable action) {
        if (this.stopping || !isEnabled())
            return false;
        try {
            getServer().getScheduler().runTask(this, () -> {
                if (!this.stopping && isEnabled())
                    action.run();
            });
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private void printBanner() {
        getLogger().info("  ____  _                       _    ____ ");
        getLogger().info(" / ___|(_)_ __  _   _ ___      / \\  / ___|");
        getLogger().info(" \\___ \\| | '_ \\| | | / __|    / _ \\| |    ");
        getLogger().info("  ___) | | | | | |_| \\__ \\   / ___ \\ |___ ");
        getLogger().info(" |____/|_|_| |_|\\__,_|___/  /_/   \\_\\____|");
        getLogger().info("  SinusAC (powered by SinusAI) v" + getDescription().getVersion());
    }

    // ==================== Публичный API (используется и SinusOP) ====================

    public static SinusAC getInstance()          { return instance; }
    public Messages getMessages()                { return this.msgs; }
    public PluginConfig getPluginConfig()        { return this.pluginConfig; }
    public ApiClient getApiClient()              { return this.apiClient; }
    public SessionManager getSessionManager()    { return this.sessionManager; }
    public FlyManager getFlyManager()            { return this.flyManager; }
    public HologramManager getHoloManager()      { return this.holoManager; }
    public boolean isReady()                     { return this.ready; }

    public boolean isAlertsEnabled()             { return this.alertsEnabled; }
    public void setAlertsEnabled(boolean v)      { this.alertsEnabled = v; }
}
