package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.universe.Universe;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Service for sending periodic heartbeat status updates to Hytale-Hub API.
 * 
 * API classes verified from HytaleServer.jar:
 * - com.hypixel.hytale.server.core.universe.Universe (getPlayers(), getPlayerCount())
 */
public class HeartbeatService {

    private static final Gson GSON = new Gson();

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running;

    private ScheduledFuture<?> heartbeatTask;

    public HeartbeatService(HytaleHubPlugin plugin, HytaleHubConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.running = new AtomicBoolean(false);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(HytaleHubConfig.HTTP_TIMEOUT))
            .build();
        
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HytaleHub-Heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::sendHeartbeat,
            0,
            HytaleHubConfig.HEARTBEAT_INTERVAL,
            TimeUnit.SECONDS
        );

        plugin.log(Level.INFO, "Heartbeat service started with interval: " + HytaleHubConfig.HEARTBEAT_INTERVAL + "s");
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        plugin.log(Level.INFO, "Heartbeat service stopped");
    }

    private void sendHeartbeat() {
        if (!running.get()) {
            return;
        }

        try {
            int playersOnline = getPlayersOnline();
            Integer playersMax = getPlayersMax();
            long timestamp = System.currentTimeMillis() / 1000;

            JsonObject payload = new JsonObject();
            payload.addProperty("playersOnline", playersOnline);
            if (playersMax != null) {
                payload.addProperty("playersMax", playersMax);
            } else {
                payload.add("playersMax", null);
            }
            payload.addProperty("ts", timestamp);

            String jsonBody = GSON.toJson(payload);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HytaleHubConfig.STATUS_ENDPOINT))
                .header("Content-Type", "application/json")
                .header("User-Agent", "HytaleHub-Plugin/1.0")
                .header("Authorization", "Bearer " + config.getToken())
                .timeout(Duration.ofMillis(HytaleHubConfig.HTTP_TIMEOUT))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                plugin.log(Level.FINE, "Heartbeat sent successfully (players: " + playersOnline + ")");
            } else {
                plugin.log(Level.WARNING, "Heartbeat failed with status: " + response.statusCode());
            }

        } catch (Exception e) {
            plugin.log(Level.WARNING, "Heartbeat error: " + e.getMessage());
        }
    }

    private int getPlayersOnline() {
        try {
            return Universe.get().getPlayerCount();
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Could not get player count: " + e.getMessage());
            return 0;
        }
    }

    private Integer getPlayersMax() {
        // Max players may not be available in Hytale API
        // Return null as per spec if not available
        try {
            var server = com.hypixel.hytale.server.core.HytaleServer.get();
            var serverConfig = server.getConfig();
            if (serverConfig != null) {
                return serverConfig.getMaxPlayers();
            }
        } catch (Exception e) {
            // Not available - return null
        }
        return null;
    }
}
