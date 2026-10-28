package de.hytalehub.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.model.Vote;
import de.hytalehub.service.VoteDedupeService;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * HTTP Endpoint for receiving votes via Nitrado:WebServer (Mode 2: HTTP).
 *
 * Endpoint: POST /HytaleHub/HytaleHubPlugin/vote
 */
public class VoteServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final long MAX_TIMESTAMP_DRIFT_SECONDS = 300; // 5 minutes

    private final transient HytaleHubPlugin plugin;
    private final transient HytaleHubConfig config;
    private final transient VoteHandler voteHandler;
    private final transient VoteDedupeService dedupeService;
    private final transient AtomicLong requestCounter;

    public VoteServlet(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            VoteHandler voteHandler,
            VoteDedupeService dedupeService) {
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.dedupeService = dedupeService;
        this.requestCounter = new AtomicLong(0);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        long requestId = requestCounter.incrementAndGet();
        resp.setContentType("application/json");
        boolean debug = config.isDebug();

        if (debug) {
            plugin.log(Level.INFO, "[HTTP #" + requestId + "] Vote request from " + req.getRemoteAddr());
        }

        try {
            // 1. Read and parse JSON body (bounded: a vote is a few hundred bytes)
            String body = readLimited(req, MAX_BODY_CHARS);
            if (body == null) {
                sendError(resp, requestId, 413, "too_large", "Request body too large", debug);
                return;
            }

            if (debug) {
                plugin.log(Level.INFO, "[HTTP #" + requestId + "] Body: " + body);
            }

            JsonObject json;
            try {
                json = JsonParser.parseString(body).getAsJsonObject();
            } catch (Exception e) {
                sendError(resp, requestId, 400, "invalid_json", "Failed to parse JSON body", debug);
                return;
            }

            // 2. Extract required fields
            if (!json.has("serviceName") || !json.has("username") ||
                    !json.has("timestamp") || !json.has("sig")) {
                sendError(resp, requestId, 400, "missing_fields",
                        "Required fields: serviceName, username, timestamp, sig", debug);
                return;
            }

            String serviceName = json.get("serviceName").getAsString();
            String username = json.get("username").getAsString();
            long timestamp = json.get("timestamp").getAsLong();
            String signature = json.get("sig").getAsString();
            // 3. Validate timestamp (prevent replay attacks)
            long now = System.currentTimeMillis() / 1000;
            if (Math.abs(now - timestamp) > MAX_TIMESTAMP_DRIFT_SECONDS) {
                sendError(resp, requestId, 401, "timestamp_expired",
                        "Timestamp too old or too far in future", debug);
                return;
            }

            // 4. Get unified token
            String token = config.getToken();
            if (!HytaleHubConfig.isTokenConfigured(token)) {
                sendError(resp, requestId, 401, "not_configured",
                        "No token configured", debug);
                return;
            }

            // 5. Verify HMAC signature
            String canonical = serviceName + "." + username + "." + timestamp;
            if (!verifyHmac(canonical, signature, token)) {
                sendError(resp, requestId, 401, "invalid_signature",
                        "HMAC signature verification failed", debug);
                return;
            }

            // 6. Check for duplicate vote
            Vote vote = new Vote(serviceName, username, timestamp);
            if (dedupeService.checkAndRecord(vote)) {
                sendError(resp, requestId, 409, "duplicate_vote",
                        "Vote already processed for this user today", debug);
                return;
            }

            // 7. Process the vote
            voteHandler.handleVote(vote);

            plugin.log(Level.INFO, "[HTTP #" + requestId + "] Vote processed: " +
                    username + " from " + serviceName);

            // 8. Send success response
            resp.setStatus(200);
            resp.getWriter().println("{\"ok\":true}");

        } catch (Exception e) {
            plugin.log(Level.WARNING, "[HTTP #" + requestId + "] Error: " + e.getMessage());
            if (debug) {
                e.printStackTrace();
            }
            sendError(resp, requestId, 500, "internal_error", "Internal server error", debug);
        }
    }

    private static final int MAX_BODY_CHARS = 8192;

    /** Reads the body up to maxChars; returns null if it is longer. */
    private static String readLimited(HttpServletRequest req, int maxChars) throws IOException {
        java.io.Reader reader = req.getReader();
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int n;
        while ((n = reader.read(buf)) != -1) {
            if (sb.length() + n > maxChars) {
                return null;
            }
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private boolean verifyHmac(String message, String signature, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] computed = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signature.toLowerCase());
            return MessageDigest.isEqual(computed, provided);
        } catch (Exception e) {
            plugin.log(Level.WARNING, "HMAC verification error: " + e.getMessage());
            return false;
        }
    }

    private void sendError(HttpServletResponse resp, long requestId, int status,
                           String error, String logMessage, boolean debug) throws IOException {
        if (debug) {
            plugin.log(Level.WARNING, "[HTTP #" + requestId + "] " + status + ": " + logMessage);
        }
        resp.setStatus(status);
        resp.getWriter().println("{\"error\":\"" + error + "\"}");
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Allow", "POST, OPTIONS");
        resp.setStatus(200);
    }

    public long getRequestCount() {
        return requestCounter.get();
    }
}
