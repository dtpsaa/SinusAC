package ru.dtpsaa.sinusac.util;

import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import ru.dtpsaa.sinusac.SinusAC;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HologramManager {

    private final SinusAC plugin;

    private final Map<UUID, Holo> holos = new ConcurrentHashMap<>();

    private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

    public HologramManager(SinusAC plugin) {
        this.plugin = plugin;

        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (this.viewers.isEmpty())
                return;
            for (Player target : Bukkit.getOnlinePlayers()) {
                Holo holo = this.holos.get(target.getUniqueId());
                if (holo != null) holo.tick(target.getLocation());
            }
        }, 1L, 2L);
    }

    public void addViewer(UUID uuid) {
        this.viewers.add(uuid);

        this.holos.values().forEach(Holo::updateVisibility);
    }

    public void removeViewer(UUID uuid) {
        this.viewers.remove(uuid);
        if (this.viewers.isEmpty()) {

            removeAll();
        } else {
            this.holos.values().forEach(Holo::updateVisibility);
        }
    }

    public boolean isViewer(UUID uuid) {
        return this.viewers.contains(uuid);
    }

    public boolean hasViewers() {
        return !this.viewers.isEmpty();
    }

    private class Holo {
        ArmorStand line1;
        ArmorStand line2;

        void spawn(Location loc) {
            line1 = create(loc.clone().add(0, 3.3, 0));
            line2 = create(loc.clone().add(0, 3.0, 0));
            updateVisibility();
        }

        void tick(Location loc) {
            if (line1 == null || line2 == null) {
                spawn(loc);
                return;
            }
            line1.teleport(loc.clone().add(0, 3.3, 0));
            line2.teleport(loc.clone().add(0, 3.0, 0));
            updateVisibility();
        }

        void update(String t1, String t2) {
            if (line1 != null) line1.setCustomName(t1);
            if (line2 != null) line2.setCustomName(t2);
        }

        void remove() {
            if (line1 != null) line1.remove();
            if (line2 != null) line2.remove();
        }

        private ArmorStand create(Location loc) {
            ArmorStand as = loc.getWorld().spawn(loc, ArmorStand.class);
            as.setInvisible(true);
            as.setMarker(true);
            as.setGravity(false);
            as.setCustomNameVisible(true);
            as.setSmall(true);
            return as;
        }

        private void updateVisibility() {
            if (line1 == null || line2 == null)
                return;
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean allowed = viewers.contains(p.getUniqueId()) && p.hasPermission("sinusac.holo");
                if (allowed) {
                    p.showEntity(plugin, line1);
                    p.showEntity(plugin, line2);
                } else {
                    p.hideEntity(plugin, line1);
                    p.hideEntity(plugin, line2);
                }
            }
        }
    }

    public void update(Player target, List<Double> history, double avg) {
        if (this.viewers.isEmpty())
            return;

        Holo holo = this.holos.computeIfAbsent(target.getUniqueId(), k -> new Holo());

        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, history.size() - 5);
        for (int i = history.size() - 1; i >= start; i--) {
            double v = history.get(i);
            sb.append(ChatColor.of(getGradientHex(v)))
                    .append(String.format("%.2f", v))
                    .append(ChatColor.of("#555555")).append(" | ");
        }
        String historyStr = sb.toString();
        if (historyStr.endsWith(" | "))
            historyStr = historyStr.substring(0, historyStr.length() - 3);

        double avgOf10 = history.stream()
                .skip(Math.max(0, history.size() - 10))
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(avg);

        String line1 = ChatColor.of("#888888") + "AVG "
                + ChatColor.of("#555555") + "["
                + ChatColor.of(getGradientHex(avgOf10))
                + String.format("%.1f%%", avgOf10 * 100.0)
                + ChatColor.of("#555555") + "]";

        holo.update(line1, historyStr);
    }

    public void remove(UUID uuid) {
        Holo holo = this.holos.remove(uuid);
        if (holo != null) holo.remove();
    }

    public void removeAll() {
        new HashSet<>(this.holos.keySet()).forEach(this::remove);
    }

    private String getGradientHex(double value) {
        double v = Math.max(0.0, Math.min(1.0, value));

        int[][] stops = {
                {127, 255,   0},
                {200, 220,   0},
                {255, 200,   0},
                {220,  60,   0},
                {139,   0,   0},
        };

        double scaled = v * (stops.length - 1);
        int idx = (int) scaled;
        if (idx >= stops.length - 1) idx = stops.length - 2;
        double t = scaled - idx;

        int r = (int) (stops[idx][0] + t * (stops[idx + 1][0] - stops[idx][0]));
        int g = (int) (stops[idx][1] + t * (stops[idx + 1][1] - stops[idx][1]));
        int b = (int) (stops[idx][2] + t * (stops[idx + 1][2] - stops[idx][2]));

        return String.format("#%02x%02x%02x", r, g, b);
    }
}
