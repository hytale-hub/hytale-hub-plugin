package de.hytalehub.votifier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
 * Accepts only signed Votifier v2 messages (HMAC-SHA256 + per-connection challenge),
 * either with binary v2 framing or as one JSON line (the format hytale-hub.de sends).
 */
public class VotifierProtocolHandler {

    private static final short V2_MAGIC = 0x733A;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Upper bound for one vote message; real votes are a few hundred bytes. */
    private static final int MAX_MESSAGE_BYTES = 8192;

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
            String token = config.getToken();
            if (!HytaleHubConfig.isTokenConfigured(token)) {
                plugin.log(Level.WARNING, "[Votifier] No API token configured (config.json \"token\"), vote rejected");
                throw new VotifierException("Server not configured");
            }

            // Peek at first 2 bytes to detect protocol
            input.mark(2);
            int byte1 = input.read();
            int byte2 = input.read();
            input.reset();
            if (byte1 < 0 || byte2 < 0) {
                throw new VotifierException("Empty input");
            }

            short magic = (short) ((byte1 << 8) | (byte2 & 0xFF));

            debugLog("First 2 bytes: 0x" + HexFormat.of().toHexDigits(magic) + " (expected v2 magic: 0x733a)");

            String wrapperJson;
            if (magic == V2_MAGIC) {
                debugLog("Detected Votifier v2 binary framing");
                wrapperJson = readV2Frame();
            } else {
                // The same signed wrapper as one JSON line (what hytale-hub.de sends)
                debugLog("No v2 magic - reading signed JSON line");
                wrapperJson = readLimitedLine();
            }

            Vote vote = verifyWrapper(wrapperJson, challenge, token);

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

        } catch (VotifierException e) {
            sendResponse("error", e.getMessage());
            plugin.log(Level.WARNING, "[Votifier] Vote rejected: " + e.getMessage());
        }
    }

    /** Binary v2 framing: magic (2 bytes), length (2 bytes), JSON wrapper. */
    private String readV2Frame() throws IOException, VotifierException {
        short magic = input.readShort();
        if (magic != V2_MAGIC) {
            throw new VotifierException("Invalid protocol magic: 0x" + HexFormat.of().toHexDigits(magic));
        }

        short length = input.readShort();
        debugLog("Message length: " + length + " bytes");

        if (length <= 0 || length > MAX_MESSAGE_BYTES) {
            throw new VotifierException("Invalid message length: " + length);
        }

        byte[] jsonBytes = new byte[length];
        input.readFully(jsonBytes);
        return new String(jsonBytes, StandardCharsets.UTF_8);
    }

    /** Reads one line, at most MAX_MESSAGE_BYTES. Longer input is rejected instead of buffered. */
    private String readLimitedLine() throws IOException, VotifierException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') break;
            if (buf.size() >= MAX_MESSAGE_BYTES) {
                throw new VotifierException("Message too long");
            }
            buf.write(b);
        }
        String line = buf.toString(StandardCharsets.UTF_8).trim();
        if (line.isEmpty()) {
            throw new VotifierException("Empty input");
        }
        return line;
    }

    /**
     * Checks a signed vote wrapper {"payload": "<json>", "signature": "<base64 HMAC-SHA256>"}.
     * The payload must carry the challenge of this connection (replay protection).
     */
    private Vote verifyWrapper(String jsonString, String expectedChallenge, String token) throws VotifierException {
        debugLog("Raw JSON wrapper: " + jsonString);

        JsonObject wrapper;
        try {
            wrapper = GSON.fromJson(jsonString, JsonObject.class);
        } catch (RuntimeException e) {
            throw new VotifierException("Invalid JSON");
        }

        if (wrapper == null || !wrapper.has("payload") || !wrapper.has("signature")) {
            throw new VotifierException("Unsupported vote format (expected signed payload)");
        }

        String payload = getJsonString(wrapper, "payload");
        String signatureEncoded = getJsonString(wrapper, "signature");
        if (payload == null || signatureEncoded == null) {
            throw new VotifierException("Missing payload or signature");
        }

        // Signature first: nothing from an unsigned payload is trusted or logged as data
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(signatureEncoded);
        } catch (IllegalArgumentException e) {
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

        JsonObject inner;
        try {
            inner = GSON.fromJson(payload, JsonObject.class);
        } catch (RuntimeException e) {
            throw new VotifierException("Invalid inner payload JSON");
        }
        if (inner == null) {
            throw new VotifierException("Empty payload");
        }

        String challenge = getJsonString(inner, "challenge");
        if (challenge == null || !MessageDigest.isEqual(
                expectedChallenge.getBytes(StandardCharsets.UTF_8), challenge.getBytes(StandardCharsets.UTF_8))) {
            throw new VotifierException("Challenge mismatch (replay protection)");
        }

        String serviceName = getJsonString(inner, "serviceName");
        String username = getJsonString(inner, "username");
        long timestamp = getJsonLong(inner, "timestamp", System.currentTimeMillis() / 1000);

        if (serviceName == null || serviceName.isEmpty()) {
            throw new VotifierException("Missing serviceName");
        }
        if (username == null || username.isEmpty()) {
            throw new VotifierException("Missing username");
        }

        debugLog("Parsed - serviceName: " + serviceName + ", username: " + username + ", timestamp: " + timestamp);

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
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return null;
    }

    private long getJsonLong(JsonObject obj, String key, long defaultValue) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            try {
                return obj.get(key).getAsLong();
            } catch (NumberFormatException e) {
                return defaultValue;
            }
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
