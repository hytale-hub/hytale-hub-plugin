package de.hytalehub.command;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.model.Vote;

import javax.annotation.Nonnull;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * /vote command - Smart voting with account linking.
 *
 * Flow:
 * 1. Check if player has linked their Hytale account to website
 * 2. If NOT linked: Show link UI/message with one-time code
 * 3. If linked: Direct vote and give rewards immediately
 */
public class VoteCommand extends AbstractPlayerCommand {

    private static final int HTTP_TIMEOUT_MS = HytaleHubConfig.HTTP_TIMEOUT;

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final HttpClient httpClient;

    public VoteCommand(HytaleHubPlugin plugin, HytaleHubConfig config, VoteHandler voteHandler, String commandName) {
        super(commandName, "Vote fuer den Server und erhalte Belohnungen", false);

        // Ab Server 0.6 braucht jeder Befehl sonst eine Permission; vorher war es canGeneratePermission()=false
        try {
            requireNoPermission();
        } catch (Throwable ignored) {
            // aeltere Server-API: canGeneratePermission() greift
        }
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .build();
    }

    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(
            @Nonnull CommandContext ctx,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef,
            @Nonnull World world) {

        String username = playerRef.getUsername();

        // If direct voting is not configured, show error
        if (voteHandler == null || config.getToken() == null || config.getToken().isBlank()) {
            ctx.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw("Vote-System nicht konfiguriert.").color("#ef4444")));
            return;
        }

        // Check if player is linked and process vote asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                processVoteCommand(ctx, playerRef, username);
            } catch (Exception e) {
                plugin.log(Level.WARNING, "Vote command error for " + username + ": " + e.getMessage());
                ctx.sendMessage(Message.raw("Ein Fehler ist aufgetreten. Bitte versuche es spaeter erneut.").color("#ef4444"));
            }
        });
    }

    private void processVoteCommand(CommandContext ctx, PlayerRef playerRef, String username) throws Exception {
        String token = config.getToken();

        // 1. Check if player is linked
        JsonObject checkResult = checkPlayerLinked(username, token);

        if (checkResult == null || !checkResult.has("ok") || !checkResult.get("ok").getAsBoolean()) {
            plugin.log(Level.WARNING, "Check linked failed, showing link");
            generateAndShowLink(ctx, playerRef, username, token);
            return;
        }

        boolean isLinked = checkResult.has("linked") && checkResult.get("linked").getAsBoolean();

        if (!isLinked) {
            generateAndShowLink(ctx, playerRef, username, token);
            return;
        }

        // 2. Player is linked - send direct vote
        JsonObject voteResult = sendDirectVote(username, token);

        if (voteResult == null || !voteResult.has("ok") || !voteResult.get("ok").getAsBoolean()) {
            String error = voteResult != null && voteResult.has("error")
                    ? voteResult.get("error").getAsString()
                    : "Unknown error";

            if ("not_linked".equals(error)) {
                generateAndShowLink(ctx, playerRef, username, token);
            } else {
                ctx.sendMessage(Message.raw("Vote fehlgeschlagen: " + error).color("#ef4444"));
            }
            return;
        }

        // 3. Process result
        String action = voteResult.has("action") ? voteResult.get("action").getAsString() : "unknown";

        if ("already_voted".equals(action)) {
            String message = config.getMessages().getAlreadyVoted();
            ctx.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw(message).color("#fbbf24")));
            return;
        }

        if ("added".equals(action)) {
            plugin.log(Level.INFO, "Direct vote successful for " + username);

            // Create vote object and process rewards
            Vote vote = new Vote("HytaleHub", username, System.currentTimeMillis() / 1000);
            voteHandler.handleVote(vote);

            // Broadcast is handled by VoteHandler
        }
    }

    private void generateAndShowLink(CommandContext ctx, PlayerRef playerRef, String username,
                                     String token) throws Exception {
        JsonObject result = generateLinkCode(username, token);

        if (result == null || !result.has("ok") || !result.get("ok").getAsBoolean()) {
            ctx.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw("Link konnte nicht generiert werden.").color("#ef4444")));
            return;
        }

        String url = result.get("url").getAsString();

        ctx.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Verknuepfe deinen Account: ").color("#d4d4d8"),
                Message.raw("[HIER KLICKEN]").color("#22c55e").bold(true).link(url)));

        plugin.log(Level.INFO, "Generated link code for " + username);
    }

    private JsonObject checkPlayerLinked(String username, String token) throws Exception {
        long ts = System.currentTimeMillis() / 1000;

        String json = String.format(
                "{\"username\":\"%s\",\"ts\":%d}",
                username, ts
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HytaleHubConfig.LINK_CHECK_ENDPOINT))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            plugin.log(Level.WARNING, "Check linked API returned " + response.statusCode());
            return null;
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject generateLinkCode(String username, String token) throws Exception {
        long ts = System.currentTimeMillis() / 1000;

        String json = String.format(
                "{\"username\":\"%s\",\"ts\":%d}",
                username, ts
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HytaleHubConfig.LINK_GENERATE_ENDPOINT))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            plugin.log(Level.WARNING, "Generate link API returned " + response.statusCode());
            return null;
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private JsonObject sendDirectVote(String username, String token) throws Exception {
        long ts = System.currentTimeMillis() / 1000;

        String json = String.format(
                "{\"username\":\"%s\",\"ts\":%d}",
                username, ts
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HytaleHubConfig.DIRECT_VOTE_ENDPOINT))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 403) {
            plugin.log(Level.WARNING, "Direct vote API returned " + response.statusCode());
            return null;
        }

        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
