package ru.dtpsaa.sinusac.collector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.FlySnapshot;
import ru.dtpsaa.sinusac.util.ApiClient;

/**
 * Collects one movement snapshot per tick and sends compact multi-player batches
 * to the private Fly engine. All Bukkit reads and actions stay on the main thread.
 */
public final class FlyManager implements Listener {

    private static final int MAX_PLAYERS_PER_REQUEST = 50;

    private final SinusAC plugin;
    private final ApiClient apiClient;
    private final SessionManager sessions;
    private final Map<UUID, List<FlySnapshot>> buffers = new HashMap<>();
    private final Map<UUID, Location> lastSafe = new HashMap<>();
    private final Set<UUID> grace = new HashSet<>();
    private final AtomicBoolean requestInFlight = new AtomicBoolean(false);
    private BukkitTask tickTask;
    private PluginConfig config;
    private long lastErrorLog;

    public FlyManager(SinusAC plugin, ApiClient apiClient,
                      SessionManager sessions, PluginConfig config) {
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.sessions = sessions;
        this.config = config;
        this.tickTask = plugin.getServer().getScheduler()
                .runTaskTimer((Plugin) plugin, this::tick, 1L, 1L);
    }

    public void updateConfig(PluginConfig config) {
        boolean wasEnabled = this.config.isCheckEnabled("fly");
        this.config = config;
        if (wasEnabled && !config.isCheckEnabled("fly")) {
            this.buffers.clear();
            this.lastSafe.clear();
            this.grace.clear();
        }
    }

    public void shutdown() {
        if (this.tickTask != null)
            this.tickTask.cancel();
        this.buffers.clear();
        this.lastSafe.clear();
        this.grace.clear();
    }

    private void tick() {
        if (!this.config.isCheckEnabled("fly"))
            return;

        int batchSize = this.config.getFlyBatchSize();
        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (!shouldCheck(player)) {
                this.buffers.remove(player.getUniqueId());
                continue;
            }

            FlySnapshot snapshot = snapshot(player);
            this.buffers.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayList<>(batchSize))
                    .add(snapshot);

            if (snapshot.onGround || snapshot.inWater || snapshot.inLava || snapshot.climbing)
                this.lastSafe.put(player.getUniqueId(), player.getLocation().clone());
        }

        if (!this.requestInFlight.get())
            dispatchReady(batchSize);
    }

    private boolean shouldCheck(Player player) {
        if (!player.isOnline() || player.isDead() || player.hasPermission("anticheat.bypass"))
            return false;
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR)
            return false;
        if (player.getAllowFlight() || player.isFlying())
            return false;
        return !this.config.isFlyBedrockOnly()
                || this.sessions.getPlatform(player.getUniqueId()).equals("bedrock");
    }

    private FlySnapshot snapshot(Player player) {
        Location location = player.getLocation();
        Material feet = location.getBlock().getType();
        Material below = location.clone().subtract(0.0D, 0.2D, 0.0D).getBlock().getType();
        String feetName = feet.name();
        String belowName = below.name();

        boolean climbing = feetName.equals("LADDER") || feetName.equals("SCAFFOLDING")
                || feetName.contains("VINE");
        boolean onSlime = below == Material.SLIME_BLOCK || belowName.endsWith("_BED");
        boolean inLava = feet == Material.LAVA;

        AttributeInstance speedAttribute = player.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        double moveSpeed = speedAttribute == null ? 0.1D : speedAttribute.getValue();

        PotionEffect jump = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        double jumpBoost = jump == null ? 0.0D : (jump.getAmplifier() + 1) * 0.75D;

        UUID uuid = player.getUniqueId();
        return new FlySnapshot(
                location.getX(), location.getY(), location.getZ(),
                player.isOnGround(), player.isInWater(), inLava, climbing,
                player.isGliding(), player.hasPotionEffect(PotionEffectType.LEVITATION),
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING),
                player.isRiptiding(), player.isInsideVehicle(), onSlime,
                moveSpeed, jumpBoost, this.grace.remove(uuid));
    }

    private void dispatchReady(int batchSize) {
        List<ApiClient.FlyBatchInput> request = new ArrayList<>();
        Map<String, UUID> requestedPlayers = new HashMap<>();

        for (Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (request.size() >= MAX_PLAYERS_PER_REQUEST)
                break;
            UUID uuid = player.getUniqueId();
            List<FlySnapshot> buffer = this.buffers.get(uuid);
            if (buffer == null || buffer.size() < batchSize)
                continue;

            int count = Math.min(batchSize, buffer.size());
            List<FlySnapshot> snapshots = new ArrayList<>(buffer.subList(0, count));
            buffer.subList(0, count).clear();
            String uuidText = uuid.toString();
            request.add(new ApiClient.FlyBatchInput(
                    player.getName(), uuidText, this.config.getFlyMinVl(), snapshots));
            requestedPlayers.put(uuidText, uuid);
        }

        if (request.isEmpty() || !this.requestInFlight.compareAndSet(false, true))
            return;

        this.apiClient.analyzeFlyBatchAsync(request).whenComplete((call, error) -> {
            this.plugin.getServer().getScheduler().runTask((Plugin) this.plugin, () -> {
                this.requestInFlight.set(false);
                if (error != null || call == null || !call.success) {
                    logApiError(error != null ? error.getMessage()
                            : call == null ? "empty response" : call.error);
                    return;
                }
                for (Map.Entry<String, ApiClient.FlyResult> entry : call.results.entrySet()) {
                    UUID uuid = requestedPlayers.get(entry.getKey());
                    if (uuid != null)
                        handleResult(uuid, entry.getValue());
                }
            });
        });
    }

    private void handleResult(UUID uuid, ApiClient.FlyResult result) {
        if (!result.flagged)
            return;
        Player player = this.plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline())
            return;

        int displayVl = this.config.getFlyMinVl();
        String message = this.plugin.getMessages().get("notify.fly")
                .replace("{player}", player.getName())
                .replace("{vl}", String.valueOf(displayVl))
                .replace("{mvl}", String.valueOf(result.mvl));
        this.plugin.getLogger().warning("[FLY] " + player.getName()
                + " | VL=" + displayVl + " | MVL=" + result.mvl
                + (result.hover ? " | HOVER" : ""));

        if (this.plugin.isAlertsEnabled()) {
            this.plugin.getServer().getOnlinePlayers().stream()
                    .filter(viewer -> viewer.hasPermission("sinusac.alerts"))
                    .forEach(viewer -> viewer.sendMessage(message));
        }

        if (this.config.isFlySetback()) {
            Location safe = this.lastSafe.get(uuid);
            if (safe != null && safe.getWorld() == player.getWorld()) {
                this.grace.add(uuid);
                player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }

        List<String> commands = this.config.getFlyPunishCommands();
        if (result.mvl >= this.config.getFlyMaxMvl() && !commands.isEmpty()) {
            for (String command : commands) {
                this.plugin.getServer().dispatchCommand(
                        this.plugin.getServer().getConsoleSender(),
                        command.replace("{player}", player.getName())
                                .replace("{reason}", "FLY")
                                .replace("{vl}", String.valueOf(displayVl))
                                .replace("{mvl}", String.valueOf(result.mvl)));
            }
            this.apiClient.resetFly(uuid.toString());
        }
    }

    private void logApiError(String error) {
        long now = System.currentTimeMillis();
        if (now - this.lastErrorLog >= 60_000L) {
            this.lastErrorLog = now;
            this.plugin.getLogger().warning("Fly API unavailable: " + error);
        }
    }

    private void resetLocal(UUID uuid, boolean notifyServer) {
        this.buffers.remove(uuid);
        this.lastSafe.remove(uuid);
        this.grace.add(uuid);
        if (notifyServer)
            this.apiClient.quitFly(uuid.toString());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        this.grace.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        this.grace.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player)
            this.grace.add(player.getUniqueId());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        resetLocal(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        resetLocal(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetLocal(event.getPlayer().getUniqueId(), true);
        this.grace.remove(event.getPlayer().getUniqueId());
    }
}
