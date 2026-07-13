package ru.dtpsaa.sinusac.command.alerts;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;

/**
 * /sinusac alerts <on|off>
 * <p>
 * ГЛОБАЛЬНЫЙ тумблер уведомлений (как в оригинальном SinusAI):
 * когда включён — алерты о подозрительных ударах рассылаются всем игрокам
 * с правом sinusac.alerts. Без аргументов показывает текущее состояние.
 */
public final class AlertsCommand implements SubCommand {

    private final SinusAC plugin;

    public AlertsCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "alerts";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            String state = this.plugin.isAlertsEnabled() ? "включены" : "выключены";
            sender.sendMessage(this.plugin.getMessages().get("cmd.alerts.usage").replace("{state}", state));
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

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 2)
            for (String s : List.of("on", "off"))
                if (s.startsWith(args[1].toLowerCase()))
                    out.add(s);
        return out;
    }
}
