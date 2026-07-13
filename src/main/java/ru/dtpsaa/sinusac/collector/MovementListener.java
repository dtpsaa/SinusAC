package ru.dtpsaa.sinusac.collector;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.dtpsaa.sinusac.SinusAC;

/**
 * Слушатель движения камеры: собирает изменения yaw/pitch в сессию игрока.
 * При выходе игрока чистит его голограмму, статус зрителя и сессию.
 */
public class MovementListener implements Listener {

    private final SessionManager sessionManager;

    public MovementListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Позиция без поворота камеры не интересна
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
            // Убираем голограмму НАД вышедшим и его статус зрителя
            plugin.getHoloManager().remove(event.getPlayer().getUniqueId());
            plugin.getHoloManager().removeViewer(event.getPlayer().getUniqueId());
        }
        this.sessionManager.onPlayerQuit(event.getPlayer().getUniqueId());
    }
}
