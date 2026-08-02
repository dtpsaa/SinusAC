package ru.dtpsaa.sinusac.command.status;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;
import ru.dtpsaa.sinusac.util.ApiClient;

/**
 * /sinusac status
 * <p>
 * Сводка: URL API, число активных сессий, состояние алертов;
 * затем асинхронно — пинг ML-сервера и статус лицензии.
 * (Строка про "игроков на записи" убрана — обучение живёт в SinusOP.)
 */
public final class StatusCommand implements SubCommand {

    private final SinusAC plugin;

    public StatusCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "status";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        int sessions = (this.plugin.getSessionManager() != null)
                ? this.plugin.getSessionManager().getActiveCount() : 0;

        sender.sendMessage(this.plugin.getMessages().get("cmd.status.api")
                .replace("{url}", this.plugin.getPluginConfig().getServerUrl()));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.server")
                .replace("{id}", this.plugin.getApiClient().getServerId()));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.sessions")
                .replace("{count}", String.valueOf(sessions)));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.alerts")
                .replace("{state}", state(this.plugin.isAlertsEnabled())));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.combat")
                .replace("{state}", state(this.plugin.getPluginConfig().isCheckEnabled("combat"))));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.fly")
                .replace("{state}", state(this.plugin.getPluginConfig().isCheckEnabled("fly"))));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.locale")
                .replace("{locale}", this.plugin.getMessages().getLocale()));

        // Пинг и лицензия — сеть, асинхронно
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            boolean alive = this.plugin.getApiClient().ping();
            ApiClient.LicenseResult lic = alive
                    ? this.plugin.getApiClient().validateLicense() : null;
            this.plugin.runOnMainThread(() -> {
                sender.sendMessage(this.plugin.getMessages().get("cmd.status.api_check")
                        .replace("{status}", this.plugin.getMessages().get(
                                alive ? "state.online" : "state.unavailable")));
                if (lic == null)
                    return;
                String days = (lic.remainingDays == -1)
                        ? this.plugin.getMessages().get("state.unlimited")
                        : this.plugin.getMessages().get("state.days")
                                .replace("{days}", String.valueOf(lic.remainingDays));
                String validColor = lic.valid ? "\u00A7a" : "\u00A7c";
                sender.sendMessage(this.plugin.getMessages().get("cmd.status.license")
                        .replace("{valid}", validColor)
                        .replace("{msg}", lic.message)
                        .replace("{days}", days));
            });
        });
    }

    private String state(boolean enabled) {
        return this.plugin.getMessages().get(enabled ? "state.enabled" : "state.disabled");
    }
}
