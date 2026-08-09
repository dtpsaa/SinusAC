package ru.dtpsaa.sinusac.collector;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.dtpsaa.sinusac.SinusAC;

public class MovementListener implements Listener {

    private final SessionManager sessionManager;

    public MovementListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {

        if (event.getFrom().getYaw() == event.getTo().getYaw()
                && event.getFrom().getPitch() == event.getTo().getPitch())
            return;
        this.sessionManager.onPlayerMove(event.getPlayer(),
                event.getTo().getYaw(), event.getTo().getPitch());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        SinusAC plugin = SinusAC.getInstance();
        if (plugin.getHoloManager() != null) {

            plugin.getHoloManager().remove(event.getPlayer().getUniqueId());
            plugin.getHoloManager().removeViewer(event.getPlayer().getUniqueId());
        }
        this.sessionManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
