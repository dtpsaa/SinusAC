package ru.dtpsaa.sinusac.command.sessions;

import org.bukkit.command.CommandSender;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;

/**
 * /sinusac sessions
 * <p>
 * Список активных сессий: игрок, платформа, число фреймов, средний % подозрения.
 * ОПЦИОНАЛЬНАЯ команда — если решишь, что не нужна, удали эту папку
 * и одну строку add(new SessionsCommand(...)) в CommandRegistry.
 */
public final class SessionsCommand implements SubCommand {

    private final SinusAC plugin;

    public SessionsCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "sessions";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (this.plugin.getSessionManager() == null) {
            sender.sendMessage(this.plugin.getMessages().get("plugin-not-ready"));
            return;
        }
        int count = this.plugin.getSessionManager().getActiveCount();
        sender.sendMessage(this.plugin.getMessages().get("cmd.sessions.count")
                .replace("{count}", String.valueOf(count)));
        if (count > 0)
            this.plugin.getSessionManager().listSessions().forEach(line -> sender.sendMessage("  " + line));
    }
}
