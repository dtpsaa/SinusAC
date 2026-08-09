package ru.dtpsaa.sinusac.collector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.BedrockCombatSnapshot;
import ru.dtpsaa.sinusac.util.ApiClient;

public final class BedrockAimManager implements Listener {

    private static final int BATCH_SIZE = 20;
    private static final int MAX_PLAYERS_PER_REQUEST = 50;
    private static final double TARGET_RANGE_SQUARED = 64.0D;

    private final SinusAC plugin;
    private final ApiClient apiClient;
    private final SessionManager sessions;
    private final Map<UUID, List<BedrockCombatSnapshot>> buffers = new HashMap<>();
    private final Map<UUID, String> sessionIds = new HashMap<>();
    private final Map<UUID, Long> sequences = new HashMap<>();
    private final Map<UUID, Long> teleportGraceUntil = new HashMap<>();
    private final Map<UUID, Long> velocityGraceUntil = new HashMap<>();
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private PluginConfig config;
    private BukkitTask tickTask;
    private long serverTick;
    private long lastErrorLog;
    private volatile boolean stopping;

    public BedrockAimManager(SinusAC plugin, ApiClient apiClient,
                             SessionManager sessions, PluginConfig config) {
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.sessions = sessions;
        this.config = config;
        this.tickTask = plugin.getServer().getScheduler()
                .runTaskTimer((Plugin) plugin, this::tick, 1L, 1L);
    }

    public void updateConfig(PluginConfig config) {
        boolean wasEnabled = this.config.isBedrockAimEnabled();
        this.config = config;
        if (wasEnabled && !config.isBedrockAimEnabled())
            clear();
    }

    public void shutdown() {
        this.stopping = true;
        this.requestInFlight.set(false);
        if (this.tickTask != null)
            this.tickTask.cancel();
        clear();
    }

    private void tick() {
        this.serverTick++;
        if (this.stopping || !this.plugin.isReady() || !this.config.isBedrockAimEnabled())
            return;

        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (!shouldCheck(player)) {
                this.buffers.remove(player.getUniqueId());
                continue;
            }
            append(player, false, nearestTarget(player));
        }
        if (!this.requestInFlight.get())
            dispatchReady();
    }

    private boolean shouldCheck(Player player) {
        return player.isOnline() && !player.isDead()
                && !player.hasPermission("anticheat.bypass")
                && this.sessions.getPlatform(player.getUniqueId()).equals("bedrock");
    }

    private Player nearestTarget(Player player) {
        Player nearest = null;
        double nearestDistance = TARGET_RANGE_SQUARED;
        for (Player candidate : this.plugin.getServer().getOnlinePlayers()) {
            if (candidate == player || candidate.isDead()
                    || candidate.getWorld() != player.getWorld())
                continue;
            double distance = candidate.getLocation().distanceSquared(player.getLocation());
            if (distance <= nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void append(Player player, boolean attack, Player targetPlayer) {
        UUID uuid = player.getUniqueId();
        Location location = player.getLocation();
        Location eye = player.getEyeLocation();
        BedrockCombatSnapshot.Target target = target(targetPlayer, eye, player);
        long sequence = this.sequences.merge(uuid, 1L, Long::sum);
        BedrockCombatSnapshot snapshot = new BedrockCombatSnapshot(
                location.getX(), location.getY(), location.getZ(),
                eye.getX(), eye.getY(), eye.getZ(), location.getYaw(), location.getPitch(),
                this.serverTick, sequence, player.getPing(), player.isOnGround(),
                player.isSprinting(), player.isSneaking(), player.isInsideVehicle(), attack,
                this.teleportGraceUntil.getOrDefault(uuid, -1L) >= this.serverTick,
                this.velocityGraceUntil.getOrDefault(uuid, -1L) >= this.serverTick, target);
        List<BedrockCombatSnapshot> buffer = this.buffers.computeIfAbsent(
                uuid, ignored -> new ArrayList<>(BATCH_SIZE + 8));
        buffer.add(snapshot);
        if (buffer.size() > 160)
            buffer.subList(0, buffer.size() - 160).clear();
    }

    private BedrockCombatSnapshot.Target target(Player target, Location eye, Player observer) {
        if (target == null)
            return null;
        BoundingBox box = target.getBoundingBox();
        Vector point = box.getMin().clone();
        point.setX(Math.max(box.getMinX(), Math.min(eye.getX(), box.getMaxX())));
        point.setY(Math.max(box.getMinY(), Math.min(eye.getY(), box.getMaxY())));
        point.setZ(Math.max(box.getMinZ(), Math.min(eye.getZ(), box.getMaxZ())));
        return new BedrockCombatSnapshot.Target(
                target.getUniqueId().toString(),
                new BedrockCombatSnapshot.TargetBox(
                        box.getMinX(), box.getMinY(), box.getMinZ(),
                        box.getMaxX(), box.getMaxY(), box.getMaxZ()),
                observer.hasLineOfSight(target), eye.toVector().distance(point));
    }

    private void dispatchReady() {
        List<ApiClient.BedrockCombatInput> request = new ArrayList<>();
        Map<String, UUID> requestedPlayers = new HashMap<>();
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (request.size() >= MAX_PLAYERS_PER_REQUEST)
                break;
            UUID uuid = player.getUniqueId();
            List<BedrockCombatSnapshot> buffer = this.buffers.get(uuid);
            if (buffer == null || buffer.size() < BATCH_SIZE)
                continue;
            int count = Math.min(160, buffer.size());
            List<BedrockCombatSnapshot> snapshots = new ArrayList<>(buffer.subList(0, count));
            buffer.subList(0, count).clear();
            String uuidText = uuid.toString();
            request.add(new ApiClient.BedrockCombatInput(
                    player.getName(), uuidText,
                    this.sessionIds.computeIfAbsent(uuid, ignored -> UUID.randomUUID().toString()),
                    snapshots));
            requestedPlayers.put(uuidText, uuid);
        }
        if (request.isEmpty() || !this.requestInFlight.compareAndSet(false, true))
            return;

        this.apiClient.analyzeBedrockCombatBatchAsync(request).whenComplete((call, error) -> {
            this.requestInFlight.set(false);
            this.plugin.runOnMainThread(() -> {
                if (this.stopping)
                    return;
                if (error != null || call == null || !call.success) {
                    logApiError(error != null ? error.getMessage()
                            : call == null ? "empty response" : call.error);
                    return;
                }
                for (Map.Entry<String, ApiClient.BedrockCombatResult> entry : call.results.entrySet()) {
                    UUID uuid = requestedPlayers.get(entry.getKey());
                    if (uuid != null)
                        handleResult(uuid, entry.getValue());
                }
            });
        });
    }

    private void handleResult(UUID uuid, ApiClient.BedrockCombatResult result) {
        if (!result.flagged)
            return;
        Player player = this.plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline())
            return;
        String reasons = result.reasons.isEmpty() ? "behavior" : String.join(",", result.reasons);
        String message = this.plugin.getMessages().get("notify.bedrock-aim")
                .replace("{player}", player.getName())
                .replace("{risk}", String.format("%.1f", result.riskScore * 100.0D))
                .replace("{vl}", String.valueOf(result.vl))
                .replace("{mvl}", String.valueOf(result.mvl));
        this.plugin.getLogger().warning("[BEDROCK-AIM] " + player.getName()
                + " | risk=" + String.format("%.1f%%", result.riskScore * 100.0D)
                + " | VL=" + result.vl + " | MVL=" + result.mvl + " | " + reasons);
        if (this.plugin.isAlertsEnabled()) {
            this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(viewer -> viewer.hasPermission("sinusac.alerts"))
                    .forEach(viewer -> viewer.sendMessage(message));
        }
    }

    private void logApiError(String error) {
        long now = System.currentTimeMillis();
        if (now - this.lastErrorLog >= 60_000L) {
            this.lastErrorLog = now;
            this.plugin.getLogger().warning("Bedrock Aim API unavailable: " + error);
        }
    }

    private void reset(UUID uuid, boolean notifyServer, boolean quit) {
        this.buffers.remove(uuid);
        this.sessionIds.remove(uuid);
        this.sequences.remove(uuid);
        this.teleportGraceUntil.remove(uuid);
        this.velocityGraceUntil.remove(uuid);
        if (notifyServer) {
            if (quit)
                this.apiClient.quitBedrockCombat(uuid.toString());
            else
                this.apiClient.resetBedrockCombat(uuid.toString());
        }
    }

    private void clear() {
        this.buffers.clear();
        this.sessionIds.clear();
        this.sequences.clear();
        this.teleportGraceUntil.clear();
        this.velocityGraceUntil.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker
                && event.getEntity() instanceof Player target
                && shouldCheck(attacker))
            append(attacker, true, target);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (this.config.isBedrockAimEnabled()
                && this.sessions.getPlatform(event.getPlayer().getUniqueId()).equals("bedrock"))
            this.teleportGraceUntil.put(event.getPlayer().getUniqueId(), this.serverTick + 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (this.config.isBedrockAimEnabled()
                && this.sessions.getPlatform(event.getPlayer().getUniqueId()).equals("bedrock"))
            this.velocityGraceUntil.put(event.getPlayer().getUniqueId(), this.serverTick + 10L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reset(uuid, this.config.isBedrockAimEnabled()
                && this.sessions.getPlatform(uuid).equals("bedrock"), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reset(uuid, this.config.isBedrockAimEnabled()
                && this.sessions.getPlatform(uuid).equals("bedrock"), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reset(uuid, this.config.isBedrockAimEnabled()
                && this.sessions.getPlatform(uuid).equals("bedrock"), true);
    }
}
