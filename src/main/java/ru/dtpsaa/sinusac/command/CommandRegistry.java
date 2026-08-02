package ru.dtpsaa.sinusac.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.command.alerts.AlertsCommand;
import ru.dtpsaa.sinusac.command.check.CheckCommand;
import ru.dtpsaa.sinusac.command.holo.HoloCommand;
import ru.dtpsaa.sinusac.command.reload.ReloadCommand;
import ru.dtpsaa.sinusac.command.sessions.SessionsCommand;
import ru.dtpsaa.sinusac.command.status.StatusCommand;

/**
 * Единая точка регистрации всех подкоманд /sinusac.
 * <p>
 * Диспетчер: проверяет право sinusac.admin, находит подкоманду по args[0]
 * и делегирует ей выполнение/таб-комплит. Нет подкоманды — показывает help.
 * <p>
 * Чтобы добавить/убрать команду — одна строка в конструкторе ниже.
 */
public final class CommandRegistry implements CommandExecutor, TabCompleter {

    private final SinusAC plugin;
    /** LinkedHashMap — чтобы порядок в таб-комплите совпадал с порядком регистрации. */
    private final Map<String, SubCommand> commands = new LinkedHashMap<>();

    public CommandRegistry(SinusAC plugin) {
        this.plugin = plugin;

        add(new StatusCommand(plugin));
        add(new AlertsCommand(plugin));
        add(new HoloCommand(plugin));
        add(new CheckCommand(plugin));
        add(new SessionsCommand(plugin)); // не нужна — просто удали эту строку и папку command/sessions
        add(new ReloadCommand(plugin));
    }

    private void add(SubCommand cmd) {
        this.commands.put(cmd.name(), cmd);
    }

    /** Привязывает диспетчер к команде из plugin.yml. */
    public void register(String commandName) {
        PluginCommand cmd = this.plugin.getCommand(commandName);
        if (cmd == null)
            throw new IllegalStateException("Команда '" + commandName + "' не объявлена в plugin.yml");
        cmd.setExecutor(this);
        cmd.setTabCompleter(this);
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
        SubCommand sub = this.commands.get(args[0].toLowerCase());
        if (sub == null) {
            printHelp(sender);
            return true;
        }
        sub.execute(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("sinusac.admin"))
            return Collections.emptyList();

        // Первый аргумент — имена подкоманд
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String name : this.commands.keySet())
                if (name.startsWith(args[0].toLowerCase()))
                    out.add(name);
            return out;
        }
        // Дальше — делегируем самой подкоманде
        SubCommand sub = this.commands.get(args[0].toLowerCase());
        return (sub != null) ? sub.tabComplete(sender, args) : Collections.emptyList();
    }

    /** Справка берётся списком из выбранного locale/*.yml. */
    private void printHelp(CommandSender sender) {
        for (String line : this.plugin.getMessages().getList("cmd.help"))
            sender.sendMessage(line);
    }
}
