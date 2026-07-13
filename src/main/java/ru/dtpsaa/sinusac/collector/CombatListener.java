package ru.dtpsaa.sinusac.collector;

import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Слушатель PvP-ударов. Фильтрует шум и передаёт валидные атаки
 * в SessionManager#onPlayerAttack. Логика 1-в-1 из SinusAI:
 *  - учитываются только удары игрока по игроку;
 *  - право anticheat.bypass освобождает от проверки;
 *  - THORNS (шипы) — не удар, игнорируем;
 *  - булава (MACE) даёт легитные резкие развороты — пропускаем;
 *  - пинг > 200 мс даёт грязные данные — пропускаем.
 */
public class CombatListener implements Listener {

    private final SessionManager sessionManager;

    public CombatListener(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player attacker;
        Entity damager = event.getDamager();
        Entity victim = event.getEntity();
        if (damager instanceof Player) {
            attacker = (Player) damager;
        } else {
            return;
        }
        if (!(victim instanceof Player))
            return;
        if (attacker.hasPermission("anticheat.bypass"))
            return;
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS)
            return;
        ItemStack mainHand = attacker.getInventory().getItemInMainHand();
        if (mainHand.getType() == Material.MACE)
            return;
        if (attacker.getPing() > 200)
            return;
        this.sessionManager.onPlayerAttack(attacker);
    }
}
