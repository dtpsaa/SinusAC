package ru.dtpsaa.sinusac.command.reload;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;
import ru.dtpsaa.sinusac.util.ApiClient;

/**
 * /sinusac reload
 * <p>
 * Перечитывает config.yml и выбранный locale/*.yml, обновляет ApiClient,
 * асинхронно перепроверяет лицензию и — только при валидной лицензии —
 * прокидывает новый конфиг в SessionManager (логика 1-в-1 из SinusAI).
 */
public final class ReloadCommand implements SubCommand {

    private final SinusAC plugin;

    public ReloadCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        this.plugin.reloadConfig();
        this.plugin.getPluginConfig().reload(this.plugin.getConfig());
        this.plugin.getMessages().load();
        this.plugin.getApiClient().updateConfig(
                this.plugin.getPluginConfig(), this.plugin.getServer().getPort());
        sender.sendMessage(this.plugin.getMessages().get("cmd.reload.start"));

        // Проверка лицензии — сетевой вызов, поэтому асинхронно
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            ApiClient.LicenseResult lic = this.plugin.getApiClient().validateLicense();
            if (lic.valid) {
                if (this.plugin.getSessionManager() != null)
                    this.plugin.getSessionManager().updateConfig(this.plugin.getPluginConfig());
                if (this.plugin.getFlyManager() != null)
                    this.plugin.getFlyManager().updateConfig(this.plugin.getPluginConfig());
                sender.sendMessage(this.plugin.getMessages().get("cmd.reload.success"));
            } else {
                sender.sendMessage(this.plugin.getMessages().get("cmd.reload.fail").replace("{msg}", lic.message));
            }
        });
    }
}
