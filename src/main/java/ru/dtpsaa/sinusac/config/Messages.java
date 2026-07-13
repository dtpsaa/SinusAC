package ru.dtpsaa.sinusac.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.dtpsaa.sinusac.SinusAC;

/**
 * Обёртка над messages.yml: подстановка {prefix} и цветов (&-коды).
 * <p>
 * get(path)     — одиночная строка;
 * getList(path) — список строк (help и любые многострочные сообщения
 *                 оформляются списками, как ты просил).
 */
public class Messages {

    private final SinusAC plugin;
    private YamlConfiguration cfg;

    public Messages(SinusAC plugin) {
        this.plugin = plugin;
        load();
    }

    /** Перечитывает messages.yml (вызывается из /sinusac reload). */
    public void load() {
        File file = new File(this.plugin.getDataFolder(), "messages.yml");
        if (!file.exists())
            this.plugin.saveResource("messages.yml", false);
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String path) {
        String msg = this.cfg.getString(path, "&cMissing path: " + path);
        return ChatColor.translateAlternateColorCodes('&',
                msg.replace("{prefix}", this.cfg.getString("prefix", "")));
    }

    public List<String> getList(String path) {
        List<String> rawList = this.cfg.getStringList(path);
        String prefix = this.cfg.getString("prefix", "");
        List<String> coloredList = new ArrayList<>();
        for (String line : rawList)
            coloredList.add(ChatColor.translateAlternateColorCodes('&', line.replace("{prefix}", prefix)));
        return coloredList;
    }
}
