package de.hytalehub.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.model.Vote;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Polls website for pending votes (Mode 3: Pull).
 * Used when TCP and HTTP modes are not available.
 */
public class VotePollService {

    private static final int INITIAL_DELAY_SECONDS = 5;

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final PendingVoteService pendingVoteService;

    private final HttpClient httpClient;
    private final AtomicBoolean running;
    private final AtomicLong pollCounter;
    private final AtomicLong voteCounter;

    private ScheduledExecutorService scheduler;

    public VotePollService(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            VoteHandler voteHandler,
            PendingVoteService pendingVoteService) {
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.pendingVoteService = pendingVoteService;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(HytaleHubConfig.HTTP_TIMEOUT))
                .build();

        this.running = new AtomicBoolean(false);
        this.pollCounter = new AtomicLong(0);
        this.voteCounter = new AtomicLong(0);
    }

    public void start() {
        if (running.getAndSet(true)) {
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HytaleHub-VotePoll");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                this::pollVotes,
                INITIAL_DELAY_SECONDS,
                HytaleHubConfig.PULL_INTERVAL,
                TimeUnit.SECONDS
        );

        plugin.log(Level.INFO, "Vote poll service started (interval: " + HytaleHubConfig.PULL_INTERVAL + "s)");
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        plugin.log(Level.INFO, "Vote poll service stopped (polls: " + pollCounter.get() +
                ", votes processed: " + voteCounter.get() + ")");
    }

    private void pollVotes() {
        long pollId = pollCounter.incrementAndGet();
        boolean debug = config.isDebug();

        try {
            String token = config.getToken();
            long ts = System.currentTimeMillis() / 1000;

            String url = String.format("%s?ts=%d&limit=50",
                    HytaleHubConfig.PULL_ENDPOINT, ts);

            if (debug) {
                plugin.log(Level.INFO, "[Poll #" + pollId + "] Requesting: " + HytaleHubConfig.PULL_ENDPOINT);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(HytaleHubConfig.HTTP_TIMEOUT))
                    .header("User-Agent", "HytaleHub-Plugin/1.0")
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                if (debug || response.statusCode() != 200) {
                    plugin.log(Level.WARNING, "[Poll #" + pollId + "] HTTP " + response.statusCode());
                }
                return;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

            if (!json.has("ok") || !json.get("ok").getAsBoolean()) {
                String error = json.has("error") ? json.get("error").getAsString() : "Unknown error";
                plugin.log(Level.WARNING, "[Poll #" + pollId + "] API error: " + error);
                return;
            }

            JsonArray votes = json.getAsJsonArray("votes");
            if (votes == null || votes.isEmpty()) {
                if (debug) {
                    plugin.log(Level.FINE, "[Poll #" + pollId + "] No pending votes");
                }
                return;
            }

            int processedCount = 0;
            for (var element : votes) {
                try {
                    JsonObject voteJson = element.getAsJsonObject();

                    // Each vote has a unique id. Remember it, so a repeated delivery never pays twice.
                    String voteId = voteJson.has("id") && !voteJson.get("id").isJsonNull()
                            ? voteJson.get("id").getAsString() : null;
                    if (pendingVoteService != null && !pendingVoteService.markVoteIdSeen(voteId)) {
                        plugin.log(Level.INFO, "[Poll #" + pollId + "] Vote " + voteId + " already received, ignored");
                        continue;
                    }

                    String serviceName = voteJson.get("service_name").getAsString();
                    String username = voteJson.get("username").getAsString();
                    long createdAt = voteJson.get("created_at").getAsLong();

                    Vote vote = new Vote(serviceName, username, createdAt);
                    voteHandler.handleVote(vote);

                    processedCount++;
                    voteCounter.incrementAndGet();

                } catch (Exception e) {
                    plugin.log(Level.WARNING, "[Poll #" + pollId + "] Failed to process vote: " + e.getMessage());
                }
            }

            plugin.log(Level.INFO, "[Poll #" + pollId + "] Processed " + processedCount + " pending vote(s)");

            boolean hasMore = json.has("hasMore") && json.get("hasMore").getAsBoolean();
            if (hasMore) {
                plugin.log(Level.INFO, "[Poll #" + pollId + "] More votes available, will fetch on next poll");
            }

        } catch (Exception e) {
            plugin.log(Level.WARNING, "[Poll #" + pollId + "] Poll error: " + e.getMessage());
            if (config.isDebug()) {
                e.printStackTrace();
            }
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getPollCount() {
        return pollCounter.get();
    }

    public long getVoteCount() {
        return voteCounter.get();
    }
}
