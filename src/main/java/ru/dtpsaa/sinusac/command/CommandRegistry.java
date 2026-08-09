package ru.dtpsaa.sinusac.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.util.ApiClient;

public final class CommandRegistry implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of(
            "status", "alerts", "holo", "check", "sessions", "reload"
    );

    private final SinusAC plugin;

    public CommandRegistry(SinusAC plugin) {
        this.plugin = plugin;
    }

    public void register(String commandName) {
        PluginCommand command = this.plugin.getCommand(commandName);
        if (command == null)
            throw new IllegalStateException("Команда '" + commandName + "' не объявлена в plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("sinusac.admin")) {
            sender.sendMessage(this.plugin.getMessages().get("no-permission"));
            return true;
        }
        if (args.length == 0) {
            printHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> status(sender);
            case "alerts" -> alerts(sender, args);
            case "holo" -> holo(sender, args);
            case "check" -> check(sender, args);
            case "sessions" -> sessions(sender);
            case "reload" -> reload(sender);
            default -> printHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sinusac.admin"))
            return Collections.emptyList();

        if (args.length == 1)
            return matching(SUBCOMMANDS, args[0]);
        if (args.length != 2)
            return Collections.emptyList();

        return switch (args[0].toLowerCase()) {
            case "alerts", "holo" -> matching(List.of("on", "off"), args[1]);
            case "check" -> matching(
                    this.plugin.getServer().getOnlinePlayers().stream().map(Player::getName).toList(),
                    args[1]
            );
            default -> Collections.emptyList();
        };
    }

    private List<String> matching(List<String> values, String prefix) {
        List<String> result = new ArrayList<>();
        String normalized = prefix.toLowerCase();
        for (String value : values)
            if (value.toLowerCase().startsWith(normalized))
                result.add(value);
        return result;
    }

    private void status(CommandSender sender) {
        int sessions = this.plugin.getSessionManager() != null
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
                .replace("{state}", state(this.plugin.getPluginConfig().isFlyCheckEnabled())));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.bedrock-aim")
                .replace("{state}", state(this.plugin.getPluginConfig().isBedrockAimEnabled())));
        sender.sendMessage(this.plugin.getMessages().get("cmd.status.locale")
                .replace("{locale}", this.plugin.getMessages().getLocale()));

        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            boolean alive = this.plugin.getApiClient().ping();
            ApiClient.LicenseResult license = alive
                    ? this.plugin.getApiClient().validateLicense() : null;
            this.plugin.runOnMainThread(() -> {
                sender.sendMessage(this.plugin.getMessages().get("cmd.status.api_check")
                        .replace("{status}", this.plugin.getMessages().get(
                                alive ? "state.online" : "state.unavailable")));
                if (license == null)
                    return;
                String days = license.remainingDays == -1
                        ? this.plugin.getMessages().get("state.unlimited")
                        : this.plugin.getMessages().get("state.days")
                                .replace("{days}", String.valueOf(license.remainingDays));
                sender.sendMessage(this.plugin.getMessages().get("cmd.status.license")
                        .replace("{valid}", license.valid ? "\u00A7a" : "\u00A7c")
                        .replace("{msg}", license.message)
                        .replace("{days}", days));
            });
        });
    }

    private void alerts(CommandSender sender, String[] args) {
        if (args.length < 2) {
            String current = this.plugin.getMessages().get(
                    this.plugin.isAlertsEnabled() ? "state.enabled" : "state.disabled");
            sender.sendMessage(this.plugin.getMessages().get("cmd.alerts.usage")
                    .replace("{state}", current));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on" -> {
                this.plugin.setAlertsEnabled(true);
                sender.sendMessage(this.plugin.getMessages().get("cmd.alerts.on"));
            }
            case "off" -> {
                this.plugin.setAlertsEnabled(false);
                sender.sendMessage(this.plugin.getMessages().get("cmd.alerts.off"));
            }
            default -> sender.sendMessage(this.plugin.getMessages().get("cmd.alerts.usage_err"));
        }
    }

    private void holo(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.plugin.getMessages().get("players-only"));
            return;
        }
        if (args.length < 2) {
            boolean viewing = this.plugin.getHoloManager().isViewer(player.getUniqueId());
            String current = this.plugin.getMessages().get(
                    viewing ? "state.enabled" : "state.disabled");
            sender.sendMessage(this.plugin.getMessages().get("cmd.holo.usage")
                    .replace("{state}", current));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on" -> {
                this.plugin.getHoloManager().addViewer(player.getUniqueId());
                sender.sendMessage(this.plugin.getMessages().get("cmd.holo.on"));
            }
            case "off" -> {
                this.plugin.getHoloManager().removeViewer(player.getUniqueId());
                sender.sendMessage(this.plugin.getMessages().get("cmd.holo.off"));
            }
            default -> sender.sendMessage(this.plugin.getMessages().get("cmd.holo.usage_err"));
        }
    }

    private void check(CommandSender sender, String[] args) {
        if (this.plugin.getSessionManager() == null) {
            sender.sendMessage(this.plugin.getMessages().get("plugin-not-ready"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(this.plugin.getMessages().get("cmd.check.usage"));
            return;
        }
        Player target = this.plugin.getServer().getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(this.plugin.getMessages().get("player-not-found")
                    .replace("{player}", args[1]));
            return;
        }
        sender.sendMessage(this.plugin.getMessages().get("cmd.check.analyzing")
                .replace("{player}", target.getName()));
        this.plugin.getSessionManager().forceAnalyze(target, sender);
    }

    private void sessions(CommandSender sender) {
        if (this.plugin.getSessionManager() == null) {
            sender.sendMessage(this.plugin.getMessages().get("plugin-not-ready"));
            return;
        }
        int count = this.plugin.getSessionManager().getActiveCount();
        sender.sendMessage(this.plugin.getMessages().get("cmd.sessions.count")
                .replace("{count}", String.valueOf(count)));
        if (count > 0)
            this.plugin.getSessionManager().listSessions()
                    .forEach(line -> sender.sendMessage("  " + line));
    }

    private void reload(CommandSender sender) {
        this.plugin.reloadConfig();
        this.plugin.getPluginConfig().reload(this.plugin.getConfig());
        this.plugin.getMessages().load();
        sender.sendMessage(this.plugin.getMessages().get("cmd.reload.start"));

        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            this.plugin.getApiClient().updateConfig(
                    this.plugin.getPluginConfig(), this.plugin.getServer().getPort());
            ApiClient.LicenseResult license = this.plugin.getApiClient().validateLicense();
            this.plugin.runOnMainThread(() -> {
                if (license.valid) {
                    this.plugin.applySubscriptionFeatures(license);
                    if (this.plugin.getSessionManager() != null)
                        this.plugin.getSessionManager().updateConfig(this.plugin.getPluginConfig());
                    if (this.plugin.getFlyManager() != null)
                        this.plugin.getFlyManager().updateConfig(this.plugin.getPluginConfig());
                    if (this.plugin.getBedrockAimManager() != null)
                        this.plugin.getBedrockAimManager().updateConfig(this.plugin.getPluginConfig());
                    sender.sendMessage(this.plugin.getMessages().get("cmd.reload.success"));
                } else {
                    sender.sendMessage(this.plugin.getMessages().get("cmd.reload.fail")
                            .replace("{msg}", license.message));
                }
            });
        });
    }

    private String state(boolean enabled) {
        return this.plugin.getMessages().get(enabled ? "state.enabled" : "state.disabled");
    }

    private void printHelp(CommandSender sender) {
        for (String line : this.plugin.getMessages().getList("cmd.help"))
            sender.sendMessage(line);
    }
}
