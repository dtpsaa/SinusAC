package ru.dtpsaa.sinusac.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.dtpsaa.sinusac.SinusAC;

/**
 * Обёртка над locale/en.yml и locale/ru.yml: подстановка {prefix} и цветов.
 * <p>
 * get(path)     — одиночная строка;
 * getList(path) — список строк (help и любые многострочные сообщения
 *                 оформляются списками, как ты просил).
 */
public class Messages {

    private final SinusAC plugin;
    private YamlConfiguration cfg;
    private YamlConfiguration fallback;
    private String locale = "en";

    public Messages(SinusAC plugin) {
        this.plugin = plugin;
        load();
    }

    /** Перечитывает выбранную локаль (вызывается из /sinusac reload). */
    public void load() {
        this.plugin.saveResource("locale/en.yml", false);
        this.plugin.saveResource("locale/ru.yml", false);
        String requested = this.plugin.getConfig().getString("locale", "en").trim().toLowerCase();
        this.locale = requested.equals("ru") ? "ru" : "en";
        if (!requested.equals("en") && !requested.equals("ru"))
            this.plugin.getLogger().warning("Unsupported locale '" + requested + "', using en.yml.");
        File fallbackFile = new File(this.plugin.getDataFolder(), "locale/en.yml");
        File file = new File(this.plugin.getDataFolder(), "locale/" + this.locale + ".yml");
        this.fallback = YamlConfiguration.loadConfiguration(fallbackFile);
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public String get(String path) {
        String msg = this.cfg.getString(path);
        if (msg == null)
            msg = this.fallback.getString(path, "&cMissing path: " + path);
        return ChatColor.translateAlternateColorCodes('&',
                msg.replace("{prefix}", prefix()));
    }

    public List<String> getList(String path) {
        List<String> rawList = this.cfg.getStringList(path);
        if (rawList.isEmpty())
            rawList = this.fallback.getStringList(path);
        String prefix = prefix();
        List<String> coloredList = new ArrayList<>();
        for (String line : rawList)
            coloredList.add(ChatColor.translateAlternateColorCodes('&', line.replace("{prefix}", prefix)));
        return coloredList;
    }

    public String getLocale() {
        return this.locale;
    }

    private String prefix() {
        String value = this.cfg.getString("prefix");
        return value != null ? value : this.fallback.getString("prefix", "");
    }
}
