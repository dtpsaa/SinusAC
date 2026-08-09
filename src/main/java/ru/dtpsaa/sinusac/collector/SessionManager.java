package ru.dtpsaa.sinusac.collector;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.Frame;
import ru.dtpsaa.sinusac.model.PlayerSession;
import ru.dtpsaa.sinusac.util.ApiClient;

public class SessionManager {

    private static final int TRAINING_BATCH_SIZE = 500;

    public interface FrameUploader {
        boolean upload(List<Frame> frames, boolean isCheater, String checkType, String platform);
    }

    private final SinusAC plugin;
    private final ApiClient apiClient;
    private PluginConfig pluginConfig;
    private final Logger logger;
    private long serverTick = 0L;

    private volatile FrameUploader uploader;

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();

    private final Map<UUID, Boolean> recordMode = new ConcurrentHashMap<>();

    private final Map<UUID, Integer> recordedFrameCounts = new ConcurrentHashMap<>();
    private final Map<UUID, String> platformCache = new ConcurrentHashMap<>();

    private final Set<UUID> combatInFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastCombatRequestTick = new ConcurrentHashMap<>();

    private final Map<UUID, List<Double>> suspicionHistory = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private boolean floodgateAvailable = false;
    private Object floodgateApi;
    private Method isFloodgatePlayer;

    public SessionManager(SinusAC plugin, ApiClient apiClient, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.pluginConfig = pluginConfig;
        this.logger = plugin.getLogger();

        try {
            Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            this.floodgateApi = apiClass.getMethod("getInstance").invoke(null);
            this.isFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            this.floodgateAvailable = true;
            this.logger.info("Floodgate API обнаружен, включена поддержка Bedrock.");
        } catch (Throwable ignored) {
            this.logger.info("Floodgate не найден, все игроки определяются как java.");
        }

        this.tickTask = plugin.getServer().getScheduler()
                .runTaskTimer((Plugin) plugin, () -> this.serverTick++, 1L, 1L);
    }

    public void setFrameUploader(FrameUploader uploader) {
        this.uploader = uploader;
    }

    public String getPlatform(UUID uuid) {
        return this.platformCache.computeIfAbsent(uuid, id -> {
            if (!this.floodgateAvailable)
                return "java";
            try {
                boolean bedrock = (boolean) this.isFloodgatePlayer.invoke(this.floodgateApi, id);
                return bedrock ? "bedrock" : "java";
            } catch (Throwable e) {
                return "java";
            }
        });
    }

    public PlayerSession getOrCreate(Player player) {
        return this.sessions.computeIfAbsent(player.getUniqueId(), uuid ->
                new PlayerSession(uuid, player.getName(),
                        this.pluginConfig.getMaxFrames(),
                        this.pluginConfig.getGcdHistorySize()));
    }

    public void onPlayerMove(Player player, float yaw, float pitch) {
        if (!this.plugin.isReady() || getPlatform(player.getUniqueId()).equals("bedrock"))
            return;
        collectMovement(player, yaw, pitch);
    }

    private PlayerSession collectMovement(Player player, float yaw, float pitch) {
        UUID uuid = player.getUniqueId();
        PlayerSession session = getOrCreate(player);
        Boolean trainingLabel = this.recordMode.get(uuid);
        if (trainingLabel != null && session.getMarkedAs() == null)
            session.setMarkedAs(trainingLabel);
        boolean added = session.addMovement(yaw, pitch, trainingLabel == null);

        if (trainingLabel != null && added) {
            this.recordedFrameCounts.merge(uuid, 1, Integer::sum);
            if (session.getFrameCount() >= TRAINING_BATCH_SIZE) {
                List<Frame> batch = session.drainFrames();
                this.logger.info(String.format(
                        "[TRAIN] %s: собрана пачка %d frames, отправляю автоматически...",
                        player.getName(), batch.size()));
                uploadSession(player.getName(), batch, trainingLabel.booleanValue(),
                        getPlatform(uuid), null);
            }
        }
        return session;
    }

    public void onPlayerAttack(Player attacker) {
        if (!this.plugin.isReady() || !this.pluginConfig.isCheckEnabled("combat"))
            return;
        UUID uuid = attacker.getUniqueId();
        if (getPlatform(uuid).equals("bedrock"))
            return;
        PlayerSession session = collectMovement(attacker,
                attacker.getLocation().getYaw(), attacker.getLocation().getPitch());

        if (session.getFrameCount() < this.pluginConfig.getMinFrames())
            return;

        if (this.recordMode.containsKey(uuid))
            return;

        int interval = this.pluginConfig.getCombatRequestIntervalTicks();
        long lastRequest = this.lastCombatRequestTick.getOrDefault(uuid, -((long) interval));
        if (this.serverTick - lastRequest < interval || !this.combatInFlight.add(uuid))
            return;
        this.lastCombatRequestTick.put(uuid, this.serverTick);

        List<Frame> frames = session.getFrames();
        int n = Math.min(frames.size(), 60);
        analyzeSession(session.name, uuid,
                frames.subList(frames.size() - n, frames.size()),
                getPlatform(uuid), null, true);
    }

    public void onPlayerQuit(UUID uuid) {
        PlayerSession session = this.sessions.remove(uuid);
        String platform = this.platformCache.remove(uuid);
        this.recordMode.remove(uuid);
        this.recordedFrameCounts.remove(uuid);
        this.suspicionHistory.remove(uuid);
        this.combatInFlight.remove(uuid);
        this.lastCombatRequestTick.remove(uuid);

        if (session != null && session.getMarkedAs() != null
                && session.getFrameCount() > 0) {
            String lbl = session.getMarkedAs() ? "CHEATER" : "LEGIT";
            this.logger.info("[TRAIN] Игрок " + session.name + " вышел во время записи. Загружаю как " + lbl + "...");
            uploadSession(session.name, session.getFrames(),
                    session.getMarkedAs().booleanValue(),
                    (platform != null) ? platform : "java", null);
        }
    }

    private void analyzeSession(String playerName, UUID uuid, List<Frame> frames,
                                String platform, CommandSender notifySender,
                                boolean releaseCombatSlot) {
        this.apiClient.analyzeAsync(frames, "combat", platform, playerName)
                .whenComplete((result, error) -> {
                    if (releaseCombatSlot)
                        this.combatInFlight.remove(uuid);
                    if (error == null && result != null)
                        this.plugin.runOnMainThread(() -> handleAnalysis(
                                playerName, uuid, frames, platform, notifySender, result));
                });
    }

    private void handleAnalysis(String playerName, UUID uuid, List<Frame> frames,
                                String platform, CommandSender notifySender,
                                ApiClient.AnalysisResult result) {
            if (result == null || result.noModel || !this.pluginConfig.isCheckEnabled("combat"))
                return;

            if (this.recordMode.containsKey(uuid))
                return;

            if (result.buffering)
                return;

            double probability = result.probability;
            double percentage = probability * 100.0D;

            PlayerSession session = this.sessions.get(uuid);
            if (session == null)
                return;

            List<Double> history = this.suspicionHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
            history.add(probability);
            if (history.size() > 20)
                history.remove(0);

            double avgProb = history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);

            if (this.plugin.getHoloManager() != null && this.plugin.getHoloManager().hasViewers()) {
                Player target = this.plugin.getServer().getPlayer(uuid);
                if (target != null) {
                    final List<Double> histSnap = new ArrayList<>(history);
                    final double avg = avgProb;
                    this.plugin.getHoloManager().update(target, histSnap, avg);
                }
            }

            double alertThreshold    = this.pluginConfig.getAlertThreshold("combat");
            double notifyThreshold   = this.pluginConfig.getNotifyThreshold("combat");
            double autoFlagThreshold = this.pluginConfig.getAutoFlagThreshold("combat");
            int maxVl                = this.pluginConfig.getMaxVl();

            boolean isVlPoint   = (probability >= notifyThreshold);
            boolean isAutoFlag  = (probability >= autoFlagThreshold);
            boolean shouldAlert = (probability >= alertThreshold);

            if (isVlPoint) {
                session.addVl();
            } else if (probability < 0.3D && session.getVl() > 0) {
                session.setVl(session.getVl() - 1);
            }

            int currentVl = session.getVl();

            String verdict;
            if (isAutoFlag) {
                verdict = "(" + this.plugin.getMessages().get("verdict.auto") + ")";
            } else if (currentVl >= maxVl) {
                verdict = "(" + this.plugin.getMessages().get("verdict.vl") + ")";
            } else if (isVlPoint) {
                verdict = "(" + this.plugin.getMessages().get("verdict.flag") + ")";
            } else {
                verdict = "(" + this.plugin.getMessages().get("verdict.legit") + ")";
            }

            this.logger.info(String.format("[%s | %s | %.1f%% | VL: %d/%d]",
                    playerName, verdict, percentage, currentVl, maxVl));

            if (shouldAlert || notifySender != null) {
                String msg = this.plugin.getMessages().get("notify.combat")
                        .replace("{player}", playerName)
                        .replace("{platform}", platform)
                        .replace("{verdict}", verdict)
                        .replace("{prob}", String.format("%.1f", percentage))
                        .replace("{vl}", String.valueOf(currentVl))
                        .replace("{max_vl}", String.valueOf(maxVl));

                if (this.plugin.isAlertsEnabled()) {
                    this.plugin.getServer().getOnlinePlayers().stream()
                            .filter(p -> p.hasPermission("sinusac.alerts"))
                            .forEach(p -> p.sendMessage(msg));
                }
                if (notifySender != null)
                    notifySender.sendMessage(msg);
            }

            if (isAutoFlag || currentVl >= maxVl) {
                String reason = isAutoFlag ? "AUTO" : "VL";
                List<Frame> framesCopy = new ArrayList<>(frames);
                String platformFinal = platform;
                executePunishment(playerName, reason, currentVl, framesCopy, platformFinal, probability);
                session.setVl(0);
            }
    }

    private void executePunishment(String playerName, String reason, int vl,
                                   List<Frame> frames, String platform, double probability) {
        for (String cmd : this.pluginConfig.getPunishCommands()) {
            this.plugin.getServer().dispatchCommand(
                    this.plugin.getServer().getConsoleSender(),
                    cmd.replace("{player}", playerName)
                            .replace("{reason}", reason)
                            .replace("{vl}", String.valueOf(vl)));
        }
        this.logger.warning("[PUNISH] " + playerName + " | " + reason + " | " + String.format("%.1f%%", probability * 100));

        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () ->
                this.apiClient.notifyBan(playerName, platform, reason, vl, probability, frames)
        );
    }

    private void uploadSession(String playerName, List<Frame> frames, boolean isCheater,
                               String platform, CommandSender notifySender) {
        FrameUploader up = this.uploader;
        if (up == null) {
            this.logger.warning("[UPLOAD] Загрузчик не подключён — пропуск (" + playerName + ")");
            if (notifySender != null)
                notifySender.sendMessage(this.plugin.getMessages().get("training.upload-unavailable"));
            return;
        }
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            boolean success = up.upload(frames, isCheater, "combat", platform);
            String label = isCheater ? "CHEATER" : "LEGIT";
            this.logger.info(String.format("[UPLOAD] %s [%s] | %s | frames=%d | %s",
                    playerName, platform, label, frames.size(), success ? "OK" : "FAIL"));
            if (notifySender != null)
                notifySender.sendMessage(this.plugin.getMessages().get("training.upload-result")
                        .replace("{frames}", String.valueOf(frames.size()))
                        .replace("{player}", playerName)
                        .replace("{platform}", platform)
                        .replace("{label}", label)
                        .replace("{result}", success ? "OK" : "FAIL"));
        });
    }

    public void forceAnalyze(Player player, CommandSender sender) {
        PlayerSession session = getOrCreate(player);
        int have = session.getFrameCount();
        int need = this.pluginConfig.getMinFrames();
        if (have < need) {
            sender.sendMessage(this.plugin.getMessages().get("training.not-enough-frames")
                    .replace("{have}", String.valueOf(have))
                    .replace("{need}", String.valueOf(need))
                    .replace("{player}", player.getName()));
            return;
        }
        String platform = getPlatform(player.getUniqueId());
        sender.sendMessage(this.plugin.getMessages().get("training.analyzing")
                .replace("{player}", player.getName())
                .replace("{platform}", platform));
        analyzeSession(player.getName(), player.getUniqueId(),
                session.getFrames(), platform, sender, false);
    }

    public void forceUpload(Player player, boolean isCheater, CommandSender sender) {
        PlayerSession session = getOrCreate(player);
        int have = session.getFrameCount();
        if (have == 0) {
            sender.sendMessage(this.plugin.getMessages().get("training.no-new-frames"));
            return;
        }
        String platform = getPlatform(player.getUniqueId());
        List<Frame> frames = session.drainFrames();
        uploadSession(player.getName(), frames, isCheater, platform, sender);
    }

    public void markPlayer(UUID uuid, Boolean isCheater) {
        PlayerSession session = this.sessions.get(uuid);
        if (isCheater == null) {
            if (session != null)
                session.setMarkedAs(null);
            this.recordMode.remove(uuid);
            this.recordedFrameCounts.remove(uuid);
        } else {
            if (session != null)
                session.setMarkedAs(isCheater);
            this.recordMode.put(uuid, isCheater);
            this.recordedFrameCounts.put(uuid, 0);
        }
    }

    public void clearPlayerFrames(UUID uuid) {
        PlayerSession session = this.sessions.get(uuid);
        if (session != null)
            session.clearFrames();
    }

    public int getFrameCount(UUID uuid) {
        if (this.recordMode.containsKey(uuid))
            return this.recordedFrameCounts.getOrDefault(uuid, 0);
        PlayerSession session = this.sessions.get(uuid);
        return (session != null) ? session.getFrameCount() : 0;
    }

    public void flushAll() {
        if (this.tickTask != null)
            this.tickTask.cancel();
        this.sessions.clear();
        this.recordMode.clear();
        this.recordedFrameCounts.clear();
        this.platformCache.clear();
        this.suspicionHistory.clear();
        this.combatInFlight.clear();
        this.lastCombatRequestTick.clear();
    }

    public int getActiveCount() {
        return this.sessions.size();
    }

    public List<String> listSessions() {
        List<String> result = new ArrayList<>();
        for (PlayerSession session : this.sessions.values()) {
            String label;
            if (this.recordMode.containsKey(session.uuid)) {
                label = "REC:" + (this.recordMode.get(session.uuid) ? "CHEATER" : "LEGIT");
            } else if (session.getMarkedAs() != null) {
                label = session.getMarkedAs() ? "MARKED:CHEATER" : "MARKED:LEGIT";
            } else {
                label = "MONITORING";
            }

            String platform = this.platformCache.getOrDefault(session.uuid, "java");
            List<Double> history = this.suspicionHistory.getOrDefault(session.uuid, List.of());
            double avg = history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);

            result.add(String.format("%s [%s] | frames=%d | avg=%.1f%% | %s",
                    session.name, platform, session.getFrameCount(), avg * 100.0D, label));
        }
        return result;
    }

    public void updateConfig(PluginConfig config) {
        this.pluginConfig = config;
    }
}
