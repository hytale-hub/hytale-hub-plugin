package de.hytalehub.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Main configuration class for HytaleHub plugin.
 * Simplified, user-friendly structure without legacy fields.
 */
public class HytaleHubConfig {

    // Hardcoded API endpoints and intervals (not configurable)
    public static final String BASE_URL = "https://www.hytale-hub.de";
    public static final String STATUS_ENDPOINT = BASE_URL + "/api/private/status/ingest";
    public static final String PULL_ENDPOINT = BASE_URL + "/api/private/votes/pending";
    public static final String DIRECT_VOTE_ENDPOINT = BASE_URL + "/api/votes/direct";
    public static final String LINK_CHECK_ENDPOINT = BASE_URL + "/api/link/game/check";
    public static final String LINK_GENERATE_ENDPOINT = BASE_URL + "/api/link/game/generate";
    public static final int HEARTBEAT_INTERVAL = 30;
    public static final int PULL_INTERVAL = 15;
    public static final int HTTP_TIMEOUT = 5000;

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    // Core settings
    private String token;

    // Feature toggles
    private FeaturesConfig features;

    // Votifier settings
    private VotifierConfig votifier;

    // Reward settings
    private RewardsConfig rewards;

    // Broadcast settings
    private BroadcastConfig broadcast;

    // Message settings
    private MessagesConfig messages;

    // Debug mode
    private boolean debug;

    /**
     * Creates a default configuration with the new simplified structure.
     */
    public static HytaleHubConfig createDefault() {
        HytaleHubConfig config = new HytaleHubConfig();

        // Core settings
        config.token = "DEIN_API_TOKEN";

        // Features - all enabled by default
        config.features = new FeaturesConfig();
        config.features.heartbeat = true;
        config.features.votifier = true;
        config.features.voteCommand = true;
        config.features.voteCommandName = "vote";
        config.features.testVoteCommand = true;
        config.features.testVoteCommandName = "testvote";
        config.features.voteReminder = true;
        config.features.reminderIntervalMinutes = 120;


        // Votifier - pull mode by default (works everywhere)
        config.votifier = new VotifierConfig();
        config.votifier.mode = "pull";
        config.votifier.port = 8192;

        // Rewards
        config.rewards = new RewardsConfig();
        config.rewards.items = List.of(
            new ItemReward("Ore_Gold", 25),
            new ItemReward("Ore_Silver", 25),
            new ItemReward("Ore_Silver_Shale", 25),
            new ItemReward("Weapon_Longsword_Flame", 1),
            new ItemReward("Weapon_Gun", 1),
            new ItemReward("Weapon_Arrow_Crude", 25),
            new ItemReward("Weapon_Arrow_Crude", 25),
            new ItemReward("Armor_Mithril_Hands", 1),
            new ItemReward("Armor_Mithril_Chest", 1),
            new ItemReward("Armor_Mithril_Head", 1),
            new ItemReward("Armor_Mithril_Legs", 1)
        );
        config.rewards.commands = List.of(
            "camshake debug Battleaxe_Frontflip_Chop 10 --player %player%"
        );
        config.rewards.description = "1x Komplette Mithril Ruestung, 1x Waffe + 50 Schuss, 25x Silver Ore, 25x Gold Ore";

        // Broadcast
        config.broadcast = new BroadcastConfig();
        config.broadcast.enabled = true;
        config.broadcast.message = "%player% hat gevotet und erhaelt: %rewards%";

        // Messages
        config.messages = new MessagesConfig();
        config.messages.voteSuccess = "";
        config.messages.voteReminder = "Du hast heute noch nicht gevotet! /vote";
        config.messages.alreadyVoted = "Du hast heute bereits gevotet!";

        // Debug off by default
        config.debug = false;

        return config;
    }

    /**
     * Loads configuration from a JSON file.
     */
    public static HytaleHubConfig loadFromFile(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        return GSON.fromJson(json, HytaleHubConfig.class);
    }

    /**
     * Saves configuration to a JSON file.
     */
    public void saveToFile(Path path) throws IOException {
        String json = GSON.toJson(this);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    // ========== Getters ==========

    public String getToken() {
        return token;
    }

    public FeaturesConfig getFeatures() {
        return features != null ? features : new FeaturesConfig();
    }

    public VotifierConfig getVotifier() {
        return votifier != null ? votifier : new VotifierConfig();
    }

    public RewardsConfig getRewards() {
        return rewards != null ? rewards : new RewardsConfig();
    }

    public BroadcastConfig getBroadcast() {
        return broadcast != null ? broadcast : new BroadcastConfig();
    }

    public MessagesConfig getMessages() {
        return messages != null ? messages : new MessagesConfig();
    }

    public boolean isDebug() {
        return debug;
    }

    // ========== Inner Config Classes ==========

    /**
     * Feature toggles for enabling/disabling plugin functionality.
     */
    public static class FeaturesConfig {
        private boolean heartbeat = true;
        private boolean votifier = true;
        private boolean voteCommand = true;
        private String voteCommandName = "vote";
        private boolean testVoteCommand = true;
        private String testVoteCommandName = "testvote";
        private boolean voteReminder = true;
        private int reminderIntervalMinutes = 120; // Default: every 2 hours

        public boolean isHeartbeat() {
            return heartbeat;
        }

        public boolean isVotifier() {
            return votifier;
        }

        public boolean isVoteCommand() {
            return voteCommand;
        }

        /**
         * Returns the command name for the vote command.
         * Defaults to "vote" if not set or blank.
         */
        public String getVoteCommandName() {
            if (voteCommandName == null || voteCommandName.isBlank()) {
                return "vote";
            }
            return voteCommandName.trim().toLowerCase();
        }

        public boolean isTestVoteCommand() {
            return testVoteCommand;
        }

        public String getTestVoteCommandName() {
            if (testVoteCommandName == null || testVoteCommandName.isBlank()) {
                return "testvote";
            }
            return testVoteCommandName.trim().toLowerCase();
        }

        public boolean isVoteReminder() {
            return voteReminder;
        }

        /**
         * Returns the interval in minutes between vote reminders.
         * Minimum is 10 minutes to prevent spam.
         */
        public int getReminderIntervalMinutes() {
            return Math.max(reminderIntervalMinutes, 10);
        }
    }

    /**
     * Votifier configuration.
     * Supports three modes: tcp, http, pull
     */
    public static class VotifierConfig {
        private String mode = "pull";
        private int port = 8192;

        /**
         * Returns the votifier mode.
         * @return "tcp", "http", or "pull" (default)
         */
        public String getMode() {
            if (mode == null || mode.isBlank()) {
                return "pull";
            }
            return mode.toLowerCase().trim();
        }

        public boolean isTcpMode() {
            return "tcp".equals(getMode());
        }

        public boolean isHttpMode() {
            return "http".equals(getMode());
        }

        public boolean isPullMode() {
            return "pull".equals(getMode());
        }

        public int getPort() {
            return port > 0 ? port : 8192;
        }

        // Legacy compatibility methods
        public String getBindHost() {
            return "0.0.0.0";
        }

        public int getBindPort() {
            return getPort();
        }
    }

    /**
     * Reward configuration for vote rewards.
     * Supports both item rewards (via /rewards container) and command rewards.
     */
    public static class RewardsConfig {
        private List<String> commands = List.of();
        private List<ItemReward> items = List.of();
        private String description = "";

        public List<String> getCommands() {
            return commands != null ? commands : List.of();
        }

        /**
         * Returns the list of item rewards.
         * These are placed in the /rewards container for safe claiming.
         */
        public List<ItemReward> getItems() {
            return items != null ? items : List.of();
        }

        /**
         * Returns the human-readable reward description.
         * Used in broadcasts: "%player% hat gevotet -> %rewards%"
         */
        public String getDescription() {
            return description != null ? description : "";
        }
    }

    /**
     * Represents a single item reward.
     */
    public static class ItemReward {
        private String itemId;   // e.g., "Ore_Gold", "Diamond"
        private int quantity;    // e.g., 3

        public ItemReward() {
            // Required for Gson
        }

        public ItemReward(String itemId, int quantity) {
            this.itemId = itemId;
            this.quantity = quantity;
        }

        public String getItemId() {
            return itemId;
        }

        public int getQuantity() {
            return quantity > 0 ? quantity : 1;
        }
    }

    /**
     * Broadcast configuration for vote announcements.
     */
    public static class BroadcastConfig {
        private boolean enabled = true;
        private String message = "%player% hat gevotet und erhaelt: %rewards%";

        public boolean isEnabled() {
            return enabled;
        }

        public String getMessage() {
            return message != null ? message : "%player% hat gevotet und erhaelt: %rewards%";
        }
    }

    /**
     * Message configuration for player notifications.
     */
    public static class MessagesConfig {
        private String voteSuccess = "Danke fuers Voten!";
        private String voteReminder = "Du hast heute noch nicht gevotet! /vote";
        private String alreadyVoted = "Du hast heute bereits gevotet!";

        public String getVoteSuccess() {
            return voteSuccess != null ? voteSuccess : "Danke fuers Voten!";
        }

        public String getVoteReminder() {
            return voteReminder != null ? voteReminder : "Du hast heute noch nicht gevotet! /vote";
        }

        public String getAlreadyVoted() {
            return alreadyVoted != null ? alreadyVoted : "Du hast heute bereits gevotet!";
        }
    }

}
