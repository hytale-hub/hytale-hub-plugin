package de.hytalehub.service;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;

/**
 * Service for sending periodic vote reminders to players who haven't voted today.
 *
 * Reminders are sent:
 * - First reminder: 60 seconds after player joins
 * - Subsequent reminders: Every X minutes (configured via reminderIntervalMinutes)
 * - Stops when: Player votes, player disconnects, or new calendar day starts
 */
public class VoteReminderService {

    private static final int INITIAL_DELAY_SECONDS = 60;

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final PendingVoteService pendingVoteService;
    private final ScheduledExecutorService scheduler;

    // Track active reminder tasks per player (username lowercase -> future)
    private final Map<String, ScheduledFuture<?>> activeReminders;

    public VoteReminderService(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            PendingVoteService pendingVoteService) {
        this.plugin = plugin;
        this.config = config;
        this.pendingVoteService = pendingVoteService;
        this.activeReminders = new ConcurrentHashMap<>();

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "HytaleHub-VoteReminder");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts periodic reminders for a player if they haven't voted today.
     * Called when a player joins the server.
     */
    public void startReminder(PlayerRef player) {
        if (!config.getFeatures().isVoteReminder()) {
            return;
        }

        String username = player.getUsername();
        String key = username.toLowerCase();

        // Already has an active reminder
        if (activeReminders.containsKey(key)) {
            return;
        }

        // Already voted today - no reminder needed
        if (pendingVoteService.hasVotedToday(username)) {
            plugin.log(Level.FINE, "Player " + username + " already voted today, no reminder scheduled");
            return;
        }

        int intervalMinutes = config.getFeatures().getReminderIntervalMinutes();

        // Schedule periodic reminder
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            executeReminder(username);
        }, INITIAL_DELAY_SECONDS, intervalMinutes * 60L, TimeUnit.SECONDS);

        activeReminders.put(key, task);
        plugin.log(Level.FINE, "Vote reminder scheduled for " + username +
                " (first in " + INITIAL_DELAY_SECONDS + "s, then every " + intervalMinutes + " min)");
    }

    /**
     * Stops the periodic reminder for a player.
     * Called when player votes or disconnects.
     */
    public void stopReminder(String username) {
        String key = username.toLowerCase();
        ScheduledFuture<?> task = activeReminders.remove(key);

        if (task != null) {
            task.cancel(false);
            plugin.log(Level.FINE, "Vote reminder stopped for " + username);
        }
    }

    /**
     * Executes a single reminder check and sends message if appropriate.
     */
    private void executeReminder(String username) {
        try {
            // Check if player voted in the meantime
            if (pendingVoteService.hasVotedToday(username)) {
                stopReminder(username);
                return;
            }

            // Find the online player
            PlayerRef player = findOnlinePlayer(username);
            if (player == null) {
                // Player went offline
                stopReminder(username);
                return;
            }

            // Send reminder message
            sendReminderMessage(player);

        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error executing vote reminder for " + username + ": " + e.getMessage());
        }
    }

    /**
     * Sends the vote reminder message to a player.
     */
    private void sendReminderMessage(PlayerRef player) {
        try {
            String message = config.getMessages().getVoteReminder();
            player.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw(message).color("#fbbf24")));
            plugin.log(Level.FINE, "Vote reminder sent to " + player.getUsername());
        } catch (Exception e) {
            plugin.log(Level.FINE, "Could not send reminder to " + player.getUsername() + ": " + e.getMessage());
        }
    }

    /**
     * Finds an online player by username.
     */
    private PlayerRef findOnlinePlayer(String username) {
        try {
            return Universe.get().getPlayerByUsername(username, NameMatching.EXACT_IGNORE_CASE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Shuts down the reminder service.
     * Called during plugin shutdown.
     */
    public void shutdown() {
        // Cancel all active reminders
        for (ScheduledFuture<?> task : activeReminders.values()) {
            task.cancel(false);
        }
        activeReminders.clear();

        // Shutdown the scheduler
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        plugin.log(Level.INFO, "Vote reminder service stopped");
    }
}
