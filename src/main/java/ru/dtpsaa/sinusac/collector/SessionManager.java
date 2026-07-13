package ru.dtpsaa.sinusac.collector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.floodgate.api.FloodgateApi;
import ru.dtpsaa.sinusac.SinusAC;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.Frame;
import ru.dtpsaa.sinusac.model.PlayerSession;
import ru.dtpsaa.sinusac.util.ApiClient;

/**
 * Ядро античита: сессии игроков, анализ ударов, VL, алерты, наказания.
 * Логика перенесена из SinusAI без изменений, кроме:
 *  - право алертов: sinusai.alerts -> sinusac.alerts;
 *  - голограммы: вместо глобального isHoloEnabled() проверяется
 *    holoManager.hasViewers() (локальный тумблер на игрока);
 *  - методы markPlayer / forceUpload / clearPlayerFrames / getFrameCount
 *    оставлены как ПУБЛИЧНЫЙ API для SinusOP (команд train/learn здесь нет).
 */
public class SessionManager {

    private final SinusAC plugin;
    private final ApiClient apiClient;
    private PluginConfig pluginConfig;
    private final Logger logger;
    private long serverTick = 0L;

    private final Map<UUID, PlayerSession> sessions = new ConcurrentHashMap<>();
    /** Игроки в режиме записи (true=CHEATER, false=LEGIT). Управляется из SinusOP. */
    private final Map<UUID, Boolean> recordMode = new ConcurrentHashMap<>();
    private final Map<UUID, String> platformCache = new ConcurrentHashMap<>();
    /** Скользящее окно вероятностей (до 20 последних ударов) для AVG и голограмм. */
    private final Map<UUID, List<Double>> suspicionHistory = new ConcurrentHashMap<>();

    private BukkitTask tickTask;
    private boolean floodgateAvailable = false;

    public SessionManager(SinusAC plugin, ApiClient apiClient, PluginConfig pluginConfig) {
        this.plugin = plugin;
        this.apiClient = apiClient;
        this.pluginConfig = pluginConfig;
        this.logger = plugin.getLogger();

        // Мягкая проверка Floodgate — поддержка Bedrock-игроков
        try {
            Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            FloodgateApi.getInstance();
            this.floodgateAvailable = true;
            this.logger.info("Floodgate API обнаружен, включена поддержка Bedrock.");
        } catch (Throwable ignored) {
            this.logger.info("Floodgate не найден, все игроки определяются как java.");
        }

        this.tickTask = plugin.getServer().getScheduler()
                .runTaskTimer((Plugin) plugin, () -> this.serverTick++, 1L, 1L);
    }

    /** Определяет платформу игрока (java/bedrock) с кэшированием. */
    private String getPlatform(UUID uuid) {
        return this.platformCache.computeIfAbsent(uuid, id -> {
            if (!this.floodgateAvailable)
                return "java";
            try {
                return FloodgateApi.getInstance().isFloodgatePlayer(id) ? "bedrock" : "java";
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

    /** Вызывается MovementListener на каждое изменение yaw/pitch. */
    public void onPlayerMove(Player player, float yaw, float pitch) {
        getOrCreate(player).addMovement(yaw, pitch);
    }

    /** Вызывается CombatListener на удар по игроку. Запускает анализ последних 60 фреймов. */
    public void onPlayerAttack(Player attacker) {
        UUID uuid = attacker.getUniqueId();
        PlayerSession session = getOrCreate(attacker);
        session.addMovement(attacker.getLocation().getYaw(), attacker.getLocation().getPitch());

        if (session.getFrameCount() < this.pluginConfig.getMinFrames())
            return;
        // Игрок на записи (train в SinusOP) — не анализируем, только копим
        if (this.recordMode.containsKey(uuid))
            return;

        List<Frame> frames = session.getFrames();
        int n = Math.min(frames.size(), 60); // последние 60 фреймов
        analyzeSession(session.name, uuid, frames.subList(frames.size() - n, frames.size()), getPlatform(uuid), null);
    }

    /** Очистка при выходе. Если игрок был на записи и набрал фреймы — автозагрузка датасета. */
    public void onPlayerQuit(UUID uuid) {
        PlayerSession session = this.sessions.remove(uuid);
        String platform = this.platformCache.remove(uuid);
        this.recordMode.remove(uuid);
        this.suspicionHistory.remove(uuid);

        if (session != null && session.getMarkedAs() != null
                && session.getFrameCount() >= this.pluginConfig.getMinFrames()) {
            String lbl = session.getMarkedAs() ? "CHEATER" : "LEGIT";
            this.logger.info("[TRAIN] Игрок " + session.name + " вышел во время записи. Загружаю как " + lbl + "...");
            uploadSession(session.name, session.getFrames(),
                    session.getMarkedAs().booleanValue(),
                    (platform != null) ? platform : "java", null);
        }
    }

    /**
     * Анализ фреймов через ML-сервер: вероятность -> история/голограммы ->
     * VL -> алерты -> наказание. notifySender != null означает ручной /sinusac check.
     */
    private void analyzeSession(String playerName, UUID uuid, List<Frame> frames,
                                String platform, CommandSender notifySender) {
        Runnable task = () -> {
            ApiClient.AnalysisResult result = this.apiClient.analyze(frames, "combat", platform, playerName);

            if (result == null || result.noModel)
                return;

            // Сервер ещё копит фреймы в буфере — ничего не делаем, ждём следующего удара
            if (result.buffering)
                return;

            double probability = result.probability;
            double percentage = probability * 100.0D;

            PlayerSession session = this.sessions.get(uuid);
            if (session == null)
                return;

            // Обновляем историю подозрений (скользящее окно 20 ударов)
            List<Double> history = this.suspicionHistory.computeIfAbsent(uuid, k -> new ArrayList<>());
            history.add(probability);
            if (history.size() > 20)
                history.remove(0);

            double avgProb = history.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);

            // Голограммы: только если есть хотя бы один локальный зритель
            if (this.plugin.getHoloManager() != null && this.plugin.getHoloManager().hasViewers()) {
                Player target = this.plugin.getServer().getPlayer(uuid);
                if (target != null) {
                    final List<Double> histSnap = new ArrayList<>(history);
                    final double avg = avgProb;
                    this.plugin.getServer().getScheduler().runTask((Plugin) this.plugin, () ->
                            this.plugin.getHoloManager().update(target, histSnap, avg)
                    );
                }
            }

            double alertThreshold    = this.pluginConfig.getAlertThreshold("combat");
            double notifyThreshold   = this.pluginConfig.getNotifyThreshold("combat");
            double autoFlagThreshold = this.pluginConfig.getAutoFlagThreshold("combat");
            int maxVl                = this.pluginConfig.getMaxVl();

            boolean isVlPoint   = (probability >= notifyThreshold);
            boolean isAutoFlag  = (probability >= autoFlagThreshold);
            boolean shouldAlert = (probability >= alertThreshold);

            // VL: добавляем при подозрении, снижаем при явно легит ударе
            if (isVlPoint) {
                session.addVl();
            } else if (probability < 0.3D && session.getVl() > 0) {
                session.setVl(session.getVl() - 1);
            }

            int currentVl = session.getVl();

            String verdict;
            if (isAutoFlag) {
                verdict = "(АВТО)";
            } else if (currentVl >= maxVl) {
                verdict = "(VL)";
            } else if (isVlPoint) {
                verdict = "(ФЛАГ)";
            } else {
                verdict = "(ЛЕГИТ)";
            }

            this.logger.info(String.format("[%s | %s | %.1f%% | VL: %d/%d]",
                    playerName, verdict, percentage, currentVl, maxVl));

            // Отправляем уведомление администраторам (право sinusac.alerts)
            if (shouldAlert || notifySender != null) {
                String msg = this.pluginConfig.getNotifyMessage()
                        .replace("{player}", playerName)
                        .replace("{platform}", platform)
                        .replace("{verdict}", verdict)
                        .replace("{prob}", String.format("%.1f", percentage))
                        .replace("{vl}", String.valueOf(currentVl))
                        .replace("{max_vl}", String.valueOf(maxVl))
                        .replace("&", "\u00A7");

                this.plugin.getServer().getScheduler().runTask((Plugin) this.plugin, () -> {
                    if (this.plugin.isAlertsEnabled()) {
                        this.plugin.getServer().getOnlinePlayers().stream()
                                .filter(p -> p.hasPermission("sinusac.alerts"))
                                .forEach(p -> p.sendMessage(msg));
                    }
                    if (notifySender != null)
                        notifySender.sendMessage(msg);
                });
            }

            // Наказание: AUTO или VL достигнут
            if (isAutoFlag || currentVl >= maxVl) {
                String reason = isAutoFlag ? "AUTO" : "VL";
                List<Frame> framesCopy = new ArrayList<>(frames);
                String platformFinal = platform;
                this.plugin.getServer().getScheduler().runTask((Plugin) this.plugin, () -> {
                    executePunishment(playerName, reason, currentVl, framesCopy, platformFinal, probability);
                    session.setVl(0);
                });
            }
        };

        if (this.pluginConfig.isAsync()) {
            this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, task);
        } else {
            task.run();
        }
    }

    /** Выполняет punish-commands из конфига и шлёт notify_ban на сервер (Telegram). */
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

        // Уведомление в Telegram — только при реальном бане
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () ->
                this.apiClient.notifyBan(playerName, platform, reason, vl, probability, frames)
        );
    }

    /** [API для SinusOP] Асинхронная загрузка размеченной сессии в датасет. */
    private void uploadSession(String playerName, List<Frame> frames, boolean isCheater,
                               String platform, CommandSender notifySender) {
        this.plugin.getServer().getScheduler().runTaskAsynchronously((Plugin) this.plugin, () -> {
            boolean success = this.apiClient.upload(frames, isCheater, "combat", platform);
            String label = isCheater ? "CHEATER" : "LEGIT";
            this.logger.info(String.format("[UPLOAD] %s [%s] | %s | frames=%d | %s",
                    playerName, platform, label, frames.size(), success ? "OK" : "FAIL"));
            if (notifySender != null)
                notifySender.sendMessage(String.format("Загружено %d фреймов для %s [%s] как %s: %s",
                        frames.size(), playerName, platform, label, success ? "OK" : "FAIL"));
        });
    }

    /** /sinusac check — принудительный анализ накопленных фреймов. */
    public void forceAnalyze(Player player, CommandSender sender) {
        PlayerSession session = getOrCreate(player);
        int have = session.getFrameCount();
        int need = this.pluginConfig.getMinFrames();
        if (have < need) {
            sender.sendMessage(String.format("Недостаточно фреймов: %d / %d для %s",
                    have, need, player.getName()));
            return;
        }
        String platform = getPlatform(player.getUniqueId());
        sender.sendMessage("Анализирую " + player.getName() + " [" + platform + "]...");
        analyzeSession(player.getName(), player.getUniqueId(), session.getFrames(), platform, sender);
    }

    /** [API для SinusOP] Выгрузка сессии как размеченного примера (train stop). */
    public void forceUpload(Player player, boolean isCheater, CommandSender sender) {
        PlayerSession session = getOrCreate(player);
        int have = session.getFrameCount();
        int need = this.pluginConfig.getMinFrames();
        if (have < need) {
            sender.sendMessage(String.format("Недостаточно фреймов: %d / %d", have, need));
            return;
        }
        String platform = getPlatform(player.getUniqueId());
        List<Frame> frames = session.getFrames();
        session.clearFrames();
        uploadSession(player.getName(), frames, isCheater, platform, sender);
    }

    /** [API для SinusOP] Пометить игрока для записи (null — снять пометку). */
    public void markPlayer(UUID uuid, Boolean isCheater) {
        PlayerSession session = this.sessions.get(uuid);
        if (isCheater == null) {
            if (session != null)
                session.setMarkedAs(null);
            this.recordMode.remove(uuid);
        } else {
            if (session != null)
                session.setMarkedAs(isCheater);
            this.recordMode.put(uuid, isCheater);
        }
    }

    /** [API для SinusOP] Сброс фреймов игрока (перед началом записи). */
    public void clearPlayerFrames(UUID uuid) {
        PlayerSession session = this.sessions.get(uuid);
        if (session != null)
            session.clearFrames();
    }

    /** [API для SinusOP] Сколько фреймов накоплено (train list). */
    public int getFrameCount(UUID uuid) {
        PlayerSession session = this.sessions.get(uuid);
        return (session != null) ? session.getFrameCount() : 0;
    }

    /** Полная очистка (onDisable). */
    public void flushAll() {
        if (this.tickTask != null)
            this.tickTask.cancel();
        this.sessions.clear();
        this.recordMode.clear();
        this.platformCache.clear();
        this.suspicionHistory.clear();
    }

    public int getActiveCount() {
        return this.sessions.size();
    }

    /** Строки для /sinusac sessions: игрок, платформа, фреймы, AVG, статус. */
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
