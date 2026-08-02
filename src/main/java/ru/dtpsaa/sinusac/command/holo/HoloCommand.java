package ru.dtpsaa.sinusac.command.holo;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.SubCommand;

/**
 * /sinusac holo <on|off>
 * <p>
 * ЛОКАЛЬНЫЙ тумблер: включает/выключает голограммы ТОЛЬКО для того,
 * кто написал команду. Никакого глобального включения "всем у кого право" —
 * каждый админ управляет видимостью лично для себя.
 * Список зрителей хранится в HologramManager (Set&lt;UUID&gt;).
 * <p>
 * Из консоли команда недоступна — консоль не может "видеть" голограммы.
 */
public final class HoloCommand implements SubCommand {

    private final SinusAC plugin;

    public HoloCommand(SinusAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "holo";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(this.plugin.getMessages().get("players-only"));
            return;
        }
        if (args.length < 2) {
            boolean viewing = this.plugin.getHoloManager().isViewer(player.getUniqueId());
            String state = this.plugin.getMessages().get(
                    viewing ? "state.enabled" : "state.disabled");
            sender.sendMessage(this.plugin.getMessages().get("cmd.holo.usage").replace("{state}", state));
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
