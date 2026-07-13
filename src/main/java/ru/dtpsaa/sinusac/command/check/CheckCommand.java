package ru.dtpsaa.sinusac.command.check;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;

/**
 * /sinusac check <игрок>
 * <p>
 * Принудительный анализ уже накопленных фреймов игрока, не дожидаясь
 * его следующего удара. Результат придёт написавшему в чат
 * (через notifySender внутри SessionManager#forceAnalyze).
 */
public final class CheckCommand implements SubCommand {

    private final SinusAC plugin;

    public CheckCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "check";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
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
            sender.sendMessage(this.plugin.getMessages().get("player-not-found").replace("{player}", args[1]));
            return;
        }
        sender.sendMessage(this.plugin.getMessages().get("cmd.check.analyzing").replace("{player}", target.getName()));
        this.plugin.getSessionManager().forceAnalyze(target, sender);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2)
            for (Player p : this.plugin.getServer().getOnlinePlayers())
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase()))
                    out.add(p.getName());
        return out;
    }
}
