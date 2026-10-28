package de.hytalehub.votifier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.model.Vote;
import de.hytalehub.service.VoteDedupeService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.logging.Level;

/**
 * Handles the Votifier protocol for a single connection.
 * Supports Votifier v2 (HMAC-based) and simple JSON mode.
 */
public class VotifierProtocolHandler {

    private static final short V2_MAGIC = 0x733A;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final VoteHandler voteHandler;
    private final VoteDedupeService dedupeService;
    private final DataInputStream input;
    private final PrintWriter output;
    private final long connectionId;
    private final boolean debug;

    public VotifierProtocolHandler(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            VoteHandler voteHandler,
            VoteDedupeService dedupeService,
            InputStream inputStream,
            OutputStream outputStream,
            long connectionId) {
        this.plugin = plugin;
        this.config = config;
        this.voteHandler = voteHandler;
        this.dedupeService = dedupeService;
        this.input = new DataInputStream(new BufferedInputStream(inputStream));
        this.output = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true);
        this.connectionId = connectionId;
        this.debug = config.isDebug();
    }

    public void handle() throws IOException {
        String challenge = generateChallenge();

        // Send greeting
        String greeting = "VOTIFIER 2 " + challenge;
        output.println(greeting);
        output.flush();

        debugLog("Sent greeting: " + greeting);

        try {
            // Peek at first 2 bytes to detect protocol
            input.mark(2);
            int byte1 = input.read();
            int byte2 = input.read();
            input.reset();

            short magic = (short) ((byte1 << 8) | (byte2 & 0xFF));

            debugLog("First 2 bytes: 0x" + HexFormat.of().toHexDigits(magic) + " (expected v2 magic: 0x733a)");

            Vote vote;
            if (magic == V2_MAGIC) {
                debugLog("Detected Votifier v2 protocol");
                vote = readV2VoteMessage(challenge);
            } else {
                debugLog("Magic mismatch - trying simple JSON mode");
                vote = readSimpleJsonVote();
            }

            if (vote != null) {
                // Check for duplicate
                if (dedupeService.checkAndRecord(vote)) {
                    debugLog("Duplicate vote rejected: " + vote.getUsername() + " from " + vote.getServiceName());
                    sendResponse("error", "duplicate vote");
                    plugin.log(Level.WARNING, "[Votifier] Duplicate vote rejected: " + vote.getUsername());
                    return;
                }

                // Process the vote
                voteHandler.handleVote(vote);
                sendResponse("ok", null);
                plugin.log(Level.INFO, "[Votifier] Vote accepted: " + vote.getUsername() + " from " + vote.getServiceName());
            }

        } catch (VotifierException e) {
            sendResponse("error", e.getMessage());
            plugin.log(Level.WARNING, "[Votifier] Vote rejected: " + e.getMessage());
        }
    }

    private Vote readV2VoteMessage(String expectedChallenge) throws IOException, VotifierException {
        // Read magic
        short magic = input.readShort();
        if (magic != V2_MAGIC) {
            throw new VotifierException("Invalid protocol magic: 0x" + HexFormat.of().toHexDigits(magic));
        }

        // Read length
        short length = input.readShort();
        debugLog("Message length: " + length + " bytes");

        if (length <= 0 || length > 4096) {
            throw new VotifierException("Invalid message length: " + length);
        }

        // Read JSON
        byte[] jsonBytes = new byte[length];
        input.readFully(jsonBytes);
        String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);

        debugLog("Raw JSON wrapper: " + jsonString);

        // Parse wrapper
        JsonObject wrapper;
        try {
            wrapper = GSON.fromJson(jsonString, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new VotifierException("Invalid JSON wrapper: " + e.getMessage());
        }

        if (wrapper == null || !wrapper.has("payload") || !wrapper.has("signature")) {
            throw new VotifierException("Missing payload or signature in wrapper");
        }

        String payload = wrapper.get("payload").getAsString();
        String signatureEncoded = wrapper.get("signature").getAsString();

        debugLog("Payload: " + payload);
        debugLog("Signature (encoded): " + signatureEncoded);

        // Parse inner payload
        JsonObject inner;
        try {
            inner = GSON.fromJson(payload, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new VotifierException("Invalid inner payload JSON: " + e.getMessage());
        }

        // Extract vote data
        String serviceName = getJsonString(inner, "serviceName");
        String username = getJsonString(inner, "username");
        long timestamp = getJsonLong(inner, "timestamp", 0);
        String challenge = getJsonString(inner, "challenge");

        debugLog("Parsed - serviceName: " + serviceName + ", username: " + username +
                ", timestamp: " + timestamp + ", challenge: " + (challenge != null ? challenge.substring(0, Math.min(8, challenge.length())) + "..." : "null"));

        // Validate required fields
        if (serviceName == null || serviceName.isEmpty()) {
            throw new VotifierException("Missing serviceName");
        }
        if (username == null || username.isEmpty()) {
            throw new VotifierException("Missing username");
        }

        // Validate challenge (replay protection)
        if (challenge == null || !expectedChallenge.equals(challenge)) {
            debugLog("Challenge mismatch! Expected: " + expectedChallenge + ", Got: " + challenge);
            throw new VotifierException("Challenge mismatch (replay protection)");
        }

        // Get token - use unified token from config
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            plugin.log(Level.WARNING, "[Votifier] No token configured!");
            throw new VotifierException("Server not configured");
        }

        // Validate signature
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureEncoded);
        } catch (IllegalArgumentException e) {
            debugLog("Failed to decode signature as Base64, trying hex...");
            try {
                signature = HexFormat.of().parseHex(signatureEncoded);
            } catch (IllegalArgumentException e2) {
                throw new VotifierException("Invalid signature encoding (not Base64 or Hex)");
            }
        }

        if (!validateHmacSignature(payload, signature, token)) {
            debugLog("Signature validation FAILED!");
            throw new VotifierException("Invalid signature");
        }

        debugLog("Signature validation PASSED");

        return new Vote(serviceName, username, timestamp);
    }

    private Vote readSimpleJsonVote() throws IOException, VotifierException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line = reader.readLine();

        if (line == null || line.isEmpty()) {
            throw new VotifierException("Empty input (not v2 protocol)");
        }

        debugLog("Simple JSON input: " + line);

        JsonObject json;
        try {
            json = GSON.fromJson(line, JsonObject.class);
        } catch (JsonSyntaxException e) {
            throw new VotifierException("Invalid JSON: " + e.getMessage());
        }

        String serviceName = getJsonString(json, "serviceName");
        String username = getJsonString(json, "username");
        long timestamp = getJsonLong(json, "timestamp", System.currentTimeMillis() / 1000);
        String signature = getJsonStringOrDefault(json, "signature", null);

        if (serviceName == null || serviceName.isEmpty()) {
            throw new VotifierException("Missing serviceName");
        }
        if (username == null || username.isEmpty()) {
            throw new VotifierException("Missing username");
        }

        // Get token
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            plugin.log(Level.WARNING, "[Votifier] No token configured!");
            throw new VotifierException("Server not configured");
        }

        // Validate signature if provided
        if (signature != null && !signature.isEmpty()) {
            String canonical = serviceName + ":" + username + ":" + timestamp;
            debugLog("Simple mode canonical: " + canonical);

            byte[] sigBytes;
            try {
                sigBytes = HexFormat.of().parseHex(signature);
            } catch (IllegalArgumentException e) {
                try {
                    sigBytes = Base64.getDecoder().decode(signature);
                } catch (IllegalArgumentException e2) {
                    throw new VotifierException("Invalid signature encoding");
                }
            }

            if (!validateHmacSignature(canonical, sigBytes, token)) {
                throw new VotifierException("Invalid signature");
            }
        } else if (!debug) {
            throw new VotifierException("Missing signature");
        } else {
            debugLog("WARNING: Accepting unsigned vote (debug mode only!)");
        }

        return new Vote(serviceName, username, timestamp);
    }

    private boolean validateHmacSignature(String message, byte[] signature, String token) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(
                    token.getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM
            );
            mac.init(keySpec);
            byte[] expectedSignature = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            if (debug) {
                debugLog("Expected signature (hex): " + HexFormat.of().formatHex(expectedSignature));
                debugLog("Received signature (hex): " + HexFormat.of().formatHex(signature));
            }

            // Constant-time comparison
            return MessageDigest.isEqual(signature, expectedSignature);

        } catch (Exception e) {
            plugin.log(Level.WARNING, "[Votifier] Signature validation error: " + e.getMessage());
            return false;
        }
    }

    private String generateChallenge() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private void sendResponse(String status, String cause) {
        JsonObject response = new JsonObject();
        response.addProperty("status", status);
        if (cause != null) {
            response.addProperty("cause", cause);
        }
        String json = GSON.toJson(response);
        output.println(json);
        output.flush();
        debugLog("Sent response: " + json);
    }

    private void debugLog(String message) {
        if (debug) {
            plugin.log(Level.INFO, "[Votifier Debug #" + connectionId + "] " + message);
        }
    }

    private String getJsonString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private String getJsonStringOrDefault(JsonObject obj, String key, String defaultValue) {
        String value = getJsonString(obj, key);
        return value != null ? value : defaultValue;
    }

    private long getJsonLong(JsonObject obj, String key, long defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsLong();
        }
        return defaultValue;
    }

    @SuppressWarnings("serial")
    public static class VotifierException extends Exception {
        public VotifierException(String message) {
            super(message);
        }
    }
}
