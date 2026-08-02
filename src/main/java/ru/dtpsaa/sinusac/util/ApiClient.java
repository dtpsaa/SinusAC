package ru.dtpsaa.sinusac.util;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import ru.dtpsaa.sinusac.config.PluginConfig;
import ru.dtpsaa.sinusac.model.Frame;
import ru.dtpsaa.sinusac.model.FlySnapshot;

/**
 * HTTP-клиент к ML-серверу SinusAI.
 * <p>
 * SinusAC использует: validateLicense, analyze, notifyBan, heartbeat, ping.
 */
public class ApiClient {

    private String baseUrl;
    private String licenseKey;
    private String serverId;

    public static class LicenseResult {
        public final boolean valid;
        public final String message;
        public final int remainingDays;

        public LicenseResult(boolean valid, String message, int remainingDays) {
            this.valid = valid;
            this.message = message;
            this.remainingDays = remainingDays;
        }

        public static LicenseResult fail(String r) {
            return new LicenseResult(false, r, 0);
        }
    }

    public static class AnalysisResult {
        public final double probability;
        public final double confidence;
        public final boolean flagged;
        public final String checkType;
        /** Модель ещё не обучена/не загружена на сервере. */
        public final boolean noModel;
        /** Сервер копит буфер фреймов — вердикта пока нет. */
        public final boolean buffering;

        public AnalysisResult(double probability, double confidence, boolean flagged,
                              String checkType, boolean noModel, boolean buffering) {
            this.probability = probability;
            this.confidence = confidence;
            this.flagged = flagged;
            this.checkType = checkType;
            this.noModel = noModel;
            this.buffering = buffering;
        }
    }

    public static class LearnResult {
        public final boolean success;
        public final String message;
        public final Map<String, String> results;

        public LearnResult(boolean success, String message, Map<String, String> results) {
            this.success = success;
            this.message = message;
            this.results = (results != null) ? results : new HashMap<>();
        }

        public static LearnResult fail(String reason) {
            return new LearnResult(false, reason, null);
        }
    }

    public static final class FlyBatchInput {
        public final String player_id;
        public final String uuid;
        public final int min_vl;
        public final List<FlySnapshot> snapshots;

        public FlyBatchInput(String playerName, String uuid, int minVl,
                             List<FlySnapshot> snapshots) {
            this.player_id = playerName;
            this.uuid = uuid;
            this.min_vl = minVl;
            this.snapshots = snapshots;
        }
    }

    public static final class FlyResult {
        public final boolean flagged;
        public final int vl;
        public final int mvl;
        public final boolean hover;
        public final Double teleportY;

        public FlyResult(boolean flagged, int vl, int mvl, boolean hover, Double teleportY) {
            this.flagged = flagged;
            this.vl = vl;
            this.mvl = mvl;
            this.hover = hover;
            this.teleportY = teleportY;
        }
    }

    public static final class FlyCallResult {
        public final boolean success;
        public final String error;
        public final Map<String, FlyResult> results;

        private FlyCallResult(boolean success, String error, Map<String, FlyResult> results) {
            this.success = success;
            this.error = error;
            this.results = results;
        }

        public static FlyCallResult ok(Map<String, FlyResult> results) {
            return new FlyCallResult(true, "", results);
        }

        public static FlyCallResult fail(String error) {
            return new FlyCallResult(false, error, new HashMap<>());
        }
    }

    private final Gson gson = new Gson();
    private HttpClient http;
    private volatile String sessionToken;

    public ApiClient(String baseUrl, String licenseKey) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.licenseKey = licenseKey;
        this.sessionToken = licenseKey;
        this.serverId = "unknown";
        this.http = buildClient();
    }

    /** Перечитывает url/ключ и автоматически формирует public-ip:bukkit-port. */
    public void updateConfig(PluginConfig config) {
        updateConfig(config, -1);
    }

    public void updateConfig(PluginConfig config, int serverPort) {
        this.baseUrl = config.getServerUrl().replaceAll("/$", "");
        this.licenseKey = config.getLicenseKey();
        this.sessionToken = this.licenseKey;
        this.http = buildClient();
        String publicIp = resolvePublicIp();
        this.serverId = (serverPort > 0 && !publicIp.equals("unknown"))
                ? publicIp + ":" + serverPort : publicIp;
    }

    public String getBaseUrl() {
        return this.baseUrl;
    }

    public String getAuthToken() {
        return (this.sessionToken != null && !this.sessionToken.isEmpty())
                ? this.sessionToken : this.licenseKey;
    }

    public String getServerId() {
        return this.serverId;
    }

    private String resolvePublicIp() {
        String[] services = {
                "https://api.ipify.org",
                "https://checkip.amazonaws.com",
                "https://icanhazip.com"
        };
        for (String url : services) {
            try {
                HttpResponse<String> resp = this.http.send(
                        req(url).GET().timeout(Duration.ofSeconds(5L)).build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body().trim();
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }

    public LicenseResult validateLicense() {
        String url = this.baseUrl + "/subscription/validate/" + this.licenseKey + "?server_id=" + this.serverId;
        try {
            HttpResponse<String> resp = this.http.send(
                    req(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject obj = this.gson.fromJson(resp.body(), JsonObject.class);
                if (obj.has("token") && !obj.get("token").isJsonNull())
                    this.sessionToken = obj.get("token").getAsString();
                boolean allowed = (!obj.has("allowed") || obj.get("allowed").getAsBoolean());
                if (!allowed)
                    return LicenseResult.fail(obj.has("reason") ? obj.get("reason").getAsString() : "Доступ запрещён");
                int rem = (obj.has("remaining_days") && !obj.get("remaining_days").isJsonNull())
                        ? obj.get("remaining_days").getAsInt() : -1;
                String plan = obj.has("plan") ? obj.get("plan").getAsString() : "unknown";
                return new LicenseResult(true, plan, rem);
            }
            return LicenseResult.fail("HTTP " + resp.statusCode() + " " + reason(resp.body()));
        } catch (Exception e) {
            return LicenseResult.fail("Ошибка подключения: " + e.getMessage());
        }
    }

    /** Non-blocking combat analysis; no Bukkit scheduler thread waits for HTTP. */
    public CompletableFuture<AnalysisResult> analyzeAsync(
            List<Frame> frames, String checkType, String platform, String playerName) {
        Map<String, Object> body = analysisBody(frames, checkType, platform, playerName);
        try {
            HttpRequest request = authReq(this.baseUrl + "/analyze")
                    .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build();
            return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> error == null
                            ? parseAnalysis(response, checkType) : null);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private Map<String, Object> analysisBody(List<Frame> frames, String checkType,
                                              String platform, String playerName) {
        Map<String, Object> body = new HashMap<>();
        body.put("platform", platform);
        body.put("check_type", normalizeCheck(checkType));
        body.put("frames", frames);
        body.put("server_id", this.serverId);
        body.put("player_id", playerName);
        return body;
    }

    private AnalysisResult parseAnalysis(HttpResponse<String> response, String checkType) {
        if (response == null || response.statusCode() != 200)
            return new AnalysisResult(0.0D, 0.0D, false, checkType, true, false);
        try {
            JsonObject obj = this.gson.fromJson(response.body(), JsonObject.class);
            boolean noModel = obj.has("no_model") && obj.get("no_model").getAsBoolean();
            boolean buffering = obj.has("buffering") && obj.get("buffering").getAsBoolean();
            return new AnalysisResult(
                    obj.get("probability").getAsDouble(),
                    obj.get("confidence").getAsDouble(),
                    obj.get("flagged").getAsBoolean(),
                    checkType, noModel, buffering);
        } catch (Exception e) {
            return null;
        }
    }

    /** Загружает размеченные фреймы в обучающий датасет. Используется только SinusOP. */
    public boolean upload(List<Frame> frames, boolean isCheater, String checkType, String platform) {
        Map<String, Object> body = new HashMap<>();
        body.put("platform", platform);
        body.put("check_type", normalizeCheck(checkType));
        body.put("is_cheater", isCheater);
        body.put("frames", frames);
        body.put("server_id", this.serverId);
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/upload")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** Запускает обучение моделей на ML-сервере. Используется только SinusOP. */
    public LearnResult learn() {
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/learn")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonObject obj = this.gson.fromJson(resp.body(), JsonObject.class);
                Map<String, String> results = new HashMap<>();
                if (obj.has("results") && obj.get("results").isJsonObject()) {
                    obj.getAsJsonObject("results").entrySet()
                            .forEach(entry -> results.put(entry.getKey(), entry.getValue().getAsString()));
                }
                return new LearnResult(true, "ok", results);
            }
            return LearnResult.fail("HTTP " + resp.statusCode() + " " + reason(resp.body()));
        } catch (Exception e) {
            return LearnResult.fail("Ошибка: " + e.getMessage());
        }
    }

    /** Sends up to 50 players in one non-blocking HTTP request. */
    public CompletableFuture<FlyCallResult> analyzeFlyBatchAsync(List<FlyBatchInput> players) {
        if (players == null || players.isEmpty())
            return CompletableFuture.completedFuture(FlyCallResult.ok(new HashMap<>()));
        Map<String, Object> body = new HashMap<>();
        body.put("server_id", this.serverId);
        body.put("players", players);
        try {
            HttpRequest request = authReq(this.baseUrl + "/fly/analyze-batch")
                    .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build();
            return this.http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .handle((response, error) -> error == null
                            ? parseFlyBatch(response)
                            : FlyCallResult.fail(error.getClass().getSimpleName()
                                    + ": " + error.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(FlyCallResult.fail(
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    private FlyCallResult parseFlyBatch(HttpResponse<String> response) {
        if (response == null || response.statusCode() != 200)
            return FlyCallResult.fail(response == null ? "empty response"
                    : "HTTP " + response.statusCode() + " " + reason(response.body()));
        try {
            JsonObject root = this.gson.fromJson(response.body(), JsonObject.class);
            Map<String, FlyResult> results = new HashMap<>();
            if (root.has("results") && root.get("results").isJsonArray()) {
                for (JsonElement item : root.getAsJsonArray("results")) {
                    JsonObject obj = item.getAsJsonObject();
                    String uuid = obj.get("uuid").getAsString();
                    Double teleportY = (obj.has("teleport_y") && !obj.get("teleport_y").isJsonNull())
                            ? obj.get("teleport_y").getAsDouble() : null;
                    results.put(uuid, new FlyResult(
                            obj.get("flagged").getAsBoolean(),
                            obj.get("vl").getAsInt(),
                            obj.get("mvl").getAsInt(),
                            obj.get("hover").getAsBoolean(), teleportY));
                }
            }
            return FlyCallResult.ok(results);
        } catch (Exception e) {
            return FlyCallResult.fail("invalid response: " + e.getMessage());
        }
    }

    public void resetFly(String uuid) {
        postFlyLifecycle("/fly/reset", uuid);
    }

    public void quitFly(String uuid) {
        postFlyLifecycle("/fly/quit", uuid);
    }

    private void postFlyLifecycle(String path, String uuid) {
        Map<String, Object> body = new HashMap<>();
        body.put("server_id", this.serverId);
        body.put("uuid", uuid);
        try {
            HttpRequest request = authReq(this.baseUrl + path)
                    .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build();
            this.http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .exceptionally(error -> null);
        } catch (Exception ignored) {}
    }

    /** Уведомление о наказании (Telegram на стороне сервера). Ошибки глотаются. */
    public void notifyBan(String playerName, String platform, String reason, int vl, double probability, List<Frame> frames) {
        Map<String, Object> body = new HashMap<>();
        body.put("player_id", playerName);
        body.put("platform", platform);
        body.put("ban_reason", reason);
        body.put("vl", vl);
        body.put("probability", probability);
        body.put("frames", frames);
        body.put("server_id", this.serverId);
        try {
            this.http.send(
                    authReq(this.baseUrl + "/notify_ban")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception ignored) {}
    }

    public boolean heartbeat(String name, String mcVersion, int online, int flagged, int banned) {
        Map<String, Object> body = new HashMap<>();
        body.put("server_id", this.serverId);
        body.put("name", (name == null) ? "" : name);
        body.put("mc_version", (mcVersion == null) ? "unknown" : mcVersion);
        body.put("online", online);
        body.put("flagged_total", flagged);
        body.put("banned_total", banned);
        try {
            HttpResponse<String> resp = this.http.send(
                    authReq(this.baseUrl + "/heartbeat")
                            .POST(HttpRequest.BodyPublishers.ofString(this.gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode() == 200);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean ping() {
        try {
            HttpResponse<String> resp = this.http.send(
                    req(this.baseUrl + "/health").GET().timeout(Duration.ofSeconds(4L)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return (resp.statusCode() == 200);
        } catch (Exception e) {
            return false;
        }
    }

    private HttpClient buildClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(6L))
                .build();
    }

    private HttpRequest.Builder req(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10L));
    }

    private HttpRequest.Builder authReq(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1)
                .header("Authorization", "Bearer " + getAuthToken())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12L));
    }

    private String reason(String body) {
        try {
            JsonElement d = this.gson.fromJson(body, JsonObject.class).get("detail");
            return (d != null) ? d.getAsString() : "unknown";
        } catch (Exception e) {
            return (body != null && body.length() < 200) ? body : "unknown";
        }
    }

    private String normalizeCheck(String checkType) {
        return (checkType.equals("killaura") || checkType.equals("aimassist")) ? "combat" : checkType;
    }
}
