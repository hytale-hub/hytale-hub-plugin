package de.hytalehub;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import de.hytalehub.command.CommandsListCommand;
import de.hytalehub.command.ResetRulesCommand;
import de.hytalehub.command.RewardsCommand;
import de.hytalehub.command.RulesCommand;
import de.hytalehub.command.TestVoteCommand;
import de.hytalehub.command.VoteCommand;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.config.RulesConfig;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.http.WebServerIntegration;
import de.hytalehub.listener.PlayerJoinListener;
import de.hytalehub.service.HeartbeatService;
import de.hytalehub.service.PendingRewardsService;
import de.hytalehub.service.PendingVoteService;
import de.hytalehub.service.VoteDedupeService;
import de.hytalehub.service.RulesAcceptanceService;
import de.hytalehub.service.VotePollService;
import de.hytalehub.service.VoteReminderService;
import de.hytalehub.votifier.VotifierServer;
import de.hytalehub.ui.RewardsPage;
import de.hytalehub.ui.RulesPage;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * HytaleHub Plugin - Official Hytale-Hub.de Integration
 *
 * Provides two main features:
 * 1. Verified Live: Periodic heartbeat status updates to Hytale-Hub API
 * 2. Votifier: Receive votes from Hytale-Hub and execute rewards/notifications
 *
 * Reference: https://hytale-docs.com/docs/modding/plugins/overview
 */
public class HytaleHubPlugin extends JavaPlugin {

    private static HytaleHubPlugin instance;

    private HytaleHubConfig config;
    private RulesConfig rulesConfig;
    private HeartbeatService heartbeatService;
    private VotifierServer votifierServer;      // Mode 1: TCP
    private VotePollService votePollService;    // Mode 3: Pull
    private WebServerIntegration webServerIntegration; // Mode 2: HTTP
    private VoteHandler voteHandler;
    private PendingVoteService pendingVoteService;
    private PendingRewardsService pendingRewardsService;
    private VoteDedupeService voteDedupeService;
    private VoteReminderService voteReminderService;
    private RulesAcceptanceService rulesAcceptanceService;

    public HytaleHubPlugin(@Nonnull JavaPluginInit init) {
        super(init);
        instance = this;
    }

    public static HytaleHubPlugin getInstance() {
        return instance;
    }

    @Override
    protected void setup() {
        log(Level.INFO, "Setting up Hytale-Hub integration plugin...");

        try {
            loadConfiguration();
        } catch (IOException e) {
            log(Level.SEVERE, "Failed to load configuration: " + e.getMessage());
            return;
        }

        initializeServices();
        registerCommands();
        registerEventListeners();

        log(Level.INFO, "Setup complete!");
    }

    @Override
    protected void start() {
        log(Level.INFO, "Starting Hytale-Hub services...");

        if (config != null && !config.isTokenConfigured()) {
            log(Level.WARNING, "No API token set in config.json (\"token\"). Live status and votes will not work until you add the token from your server dashboard on hytale-hub.de.");
        }

        // Start Verified Live heartbeat
        if (config.getFeatures().isHeartbeat()) {
            heartbeatService.start();
            log(Level.INFO, "Heartbeat service started (interval: "
                    + HytaleHubConfig.HEARTBEAT_INTERVAL + "s)");
        } else {
            log(Level.INFO, "Heartbeat disabled in config");
        }

        // Start Votifier based on mode
        if (config.getFeatures().isVotifier()) {
            String mode = config.getVotifier().getMode();

            switch (mode) {
                case "tcp":
                    if (votifierServer != null) {
                        try {
                            votifierServer.start();
                            log(Level.INFO, "Votifier TCP server started on port "
                                    + config.getVotifier().getPort());
                        } catch (IOException e) {
                            log(Level.SEVERE, "Failed to start Votifier TCP server: " + e.getMessage());
                        }
                    }
                    break;

                case "http":
                    if (webServerIntegration != null) {
                        if (webServerIntegration.isWebServerAvailable()) {
                            if (webServerIntegration.register()) {
                                log(Level.INFO, "Votifier HTTP endpoint registered");
                            } else {
                                log(Level.SEVERE, "Failed to register Votifier HTTP endpoint");
                            }
                        } else {
                            log(Level.WARNING, "Votifier HTTP mode enabled but WebServer plugin not found!");
                            log(Level.WARNING, "Install Nitrado:WebServer plugin or switch to 'pull' mode");
                        }
                    }
                    break;

                case "pull":
                    if (votePollService != null) {
                        votePollService.start();
                        log(Level.INFO, "Votifier Pull service started (interval: "
                                + HytaleHubConfig.PULL_INTERVAL + "s)");
                    }
                    break;

                default:
                    log(Level.WARNING, "Unknown votifier mode: " + mode);
            }
        } else {
            log(Level.INFO, "Votifier disabled in config");
        }

        log(Level.INFO, "Hytale-Hub plugin started successfully!");
    }

    @Override
    protected void shutdown() {
        log(Level.INFO, "Shutting down Hytale-Hub services...");

        if (heartbeatService != null) {
            heartbeatService.stop();
        }

        if (votifierServer != null) {
            votifierServer.stop();
        }
        if (votePollService != null) {
            votePollService.stop();
        }
        if (webServerIntegration != null) {
            webServerIntegration.shutdown();
        }

        if (voteReminderService != null) {
            voteReminderService.shutdown();
        }

        if (pendingVoteService != null) {
            try {
                pendingVoteService.saveToFile();
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to save pending votes: " + e.getMessage());
            }
        }

        if (pendingRewardsService != null) {
            try {
                pendingRewardsService.saveToFile();
            } catch (IOException e) {
                log(Level.SEVERE, "Failed to save pending rewards: " + e.getMessage());
            }
        }

        // rulesAcceptanceService saves immediately on markAccepted(), no need to save on shutdown

        try {
            RulesPage.shutdownTimer();
        } catch (Throwable e) {
            // Class may not be loaded yet; nothing to stop
        }

        log(Level.INFO, "Shutdown complete!");
    }

    private void loadConfiguration() throws IOException {
        Path configPath = getDataDirectory().resolve("config.json");

        if (!Files.exists(configPath)) {
            Files.createDirectories(configPath.getParent());
            config = HytaleHubConfig.createDefault();
            config.saveToFile(configPath);
            log(Level.INFO, "Created default configuration at: " + configPath);
        } else {
            config = HytaleHubConfig.loadFromFile(configPath);
            log(Level.INFO, "Loaded configuration from: " + configPath);
        }

        // Load rules config (separate file)
        Path rulesConfigPath = getDataDirectory().resolve("rules_config.json");

        if (!Files.exists(rulesConfigPath)) {
            rulesConfig = RulesConfig.createDefault();
            rulesConfig.saveToFile(rulesConfigPath);
            log(Level.INFO, "Created default rules configuration at: " + rulesConfigPath);
        } else {
            rulesConfig = RulesConfig.loadFromFile(rulesConfigPath);
            log(Level.INFO, "Loaded rules configuration from: " + rulesConfigPath);
        }
    }

    private void initializeServices() {
        // Initialize pending vote service
        Path pendingVotesPath = getDataDirectory().resolve("pending_votes.json");
        pendingVoteService = new PendingVoteService(pendingVotesPath);
        try {
            pendingVoteService.loadFromFile();
        } catch (Throwable e) {
            log(Level.WARNING, "Could not load pending votes: " + e.getMessage());
        }

        // Initialize vote deduplication service
        voteDedupeService = new VoteDedupeService();

        // Initialize rules acceptance service
        Path rulesPath = getDataDirectory().resolve("accepted_rules.json");
        rulesAcceptanceService = new RulesAcceptanceService(rulesPath);
        try {
            rulesAcceptanceService.loadFromFile();
        } catch (Throwable e) {
            log(Level.WARNING, "Could not load accepted rules: " + e.getMessage());
        }

        // Initialize pending rewards service
        Path pendingRewardsPath = getDataDirectory().resolve("pending_rewards.json");
        pendingRewardsService = new PendingRewardsService(pendingRewardsPath);
        try {
            pendingRewardsService.loadFromFile();
        } catch (Throwable e) {
            log(Level.WARNING, "Could not load pending rewards: " + e.getMessage());
        }

        // Initialize vote handler
        voteHandler = new VoteHandler(this, config, pendingVoteService);
        voteHandler.setPendingRewardsService(pendingRewardsService);

        // Initialize vote reminder service (if enabled)
        if (config.getFeatures().isVoteReminder()) {
            voteReminderService = new VoteReminderService(this, config, pendingVoteService);
            voteHandler.setVoteReminderService(voteReminderService);
            log(Level.INFO, "Vote reminder service initialized (interval: "
                    + config.getFeatures().getReminderIntervalMinutes() + " min)");
        }

        // Initialize heartbeat service
        heartbeatService = new HeartbeatService(this, config);

        // Initialize Votifier based on mode
        String mode = config.getVotifier().getMode();
        log(Level.INFO, "Votifier mode: " + mode);

        switch (mode) {
            case "tcp":
                votifierServer = new VotifierServer(this, config, voteHandler, voteDedupeService);
                break;

            case "http":
                webServerIntegration = new WebServerIntegration(this, config, voteHandler, voteDedupeService);
                webServerIntegration.findWebServer();
                break;

            case "pull":
                votePollService = new VotePollService(this, config, voteHandler, pendingVoteService);
                break;

            default:
                log(Level.WARNING, "Unknown votifier mode: " + mode + ", defaulting to pull");
                votePollService = new VotePollService(this, config, voteHandler, pendingVoteService);
        }
    }

    private void registerCommands() {
        // Register /vote command if enabled
        if (config.getFeatures().isVoteCommand()) {
            String commandName = config.getFeatures().getVoteCommandName();
            getCommandRegistry().registerCommand(new VoteCommand(this, config, voteHandler, commandName));
            log(Level.INFO, "Command registered: /" + commandName);
        }

        // Register /rewards command for claiming item rewards
        getCommandRegistry().registerCommand(new RewardsCommand(this, pendingRewardsService));
        log(Level.INFO, "Command registered: /rewards");

        // Register /resetrules command if enabled
        if (rulesConfig.isResetRulesCommand()) {
            String resetRulesCmdName = rulesConfig.getResetRulesCommandName();
            getCommandRegistry().registerCommand(new ResetRulesCommand(this, rulesAcceptanceService, resetRulesCmdName));
            log(Level.INFO, "Command registered: /" + resetRulesCmdName);
        }

        // Register /rules command if enabled
        if (rulesConfig.isRulesCommand()) {
            String rulesCmdName = rulesConfig.getRulesCommandName();
            getCommandRegistry().registerCommand(new RulesCommand(this, rulesConfig, rulesCmdName));
            log(Level.INFO, "Command registered: /" + rulesCmdName);
        }

        // Register /commands command if enabled
        if (rulesConfig.isCommandsCommand()) {
            String commandsCmdName = rulesConfig.getCommandsCommandName();
            getCommandRegistry().registerCommand(new CommandsListCommand(this, rulesConfig, commandsCmdName));
            log(Level.INFO, "Command registered: /" + commandsCmdName);
        }

        // Register /testvote command if enabled
        if (config.getFeatures().isTestVoteCommand()) {
            String testCommandName = config.getFeatures().getTestVoteCommandName();
            getCommandRegistry().registerCommand(new TestVoteCommand(this, voteHandler, testCommandName));
            log(Level.INFO, "Command registered: /" + testCommandName);
        }
    }

    private void registerEventListeners() {
        PlayerJoinListener listener = new PlayerJoinListener(this, voteHandler, voteReminderService, pendingRewardsService, rulesAcceptanceService);
        getEventRegistry().register(PlayerConnectEvent.class, listener::onPlayerJoin);
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, listener::onPlayerReady);

        // Register disconnect event for cleanup
        getEventRegistry().register(PlayerDisconnectEvent.class, event -> {
            if (event.getPlayerRef() != null) {
                String username = event.getPlayerRef().getUsername();

                listener.onPlayerLeave(username);

                // Stop vote reminders
                if (voteReminderService != null) {
                    voteReminderService.stopReminder(username);
                }

                // Force-close any open rewards page
                RewardsPage.forceClosePage(username);

                // Force-close any open rules page
                RulesPage.forceClosePage(username);
            }
        });

        log(Level.INFO, "Event listeners registered");
    }

    public void log(Level level, String message) {
        getLogger().at(level).log("[HytaleHub] " + message);
    }

    public HytaleHubConfig getConfig() {
        return config;
    }

    public RulesConfig getRulesConfig() {
        return rulesConfig;
    }

    public VoteHandler getVoteHandler() {
        return voteHandler;
    }

    public PendingVoteService getPendingVoteService() {
        return pendingVoteService;
    }

    public PendingRewardsService getPendingRewardsService() {
        return pendingRewardsService;
    }
}
