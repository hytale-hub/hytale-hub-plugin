package de.hytalehub.handler;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.HytaleHubConfig;
import de.hytalehub.model.PendingVote;
import de.hytalehub.model.RewardItem;
import de.hytalehub.model.Vote;
import de.hytalehub.service.PendingRewardsService;
import de.hytalehub.service.PendingVoteService;
import de.hytalehub.service.VoteReminderService;

import java.util.Optional;
import java.util.logging.Level;

/**
 * Handles incoming votes and executes rewards.
 */
public class VoteHandler {

    private final HytaleHubPlugin plugin;
    private final HytaleHubConfig config;
    private final PendingVoteService pendingVoteService;
    private PendingRewardsService pendingRewardsService;
    private VoteReminderService voteReminderService;

    public VoteHandler(
            HytaleHubPlugin plugin,
            HytaleHubConfig config,
            PendingVoteService pendingVoteService) {
        this.plugin = plugin;
        this.config = config;
        this.pendingVoteService = pendingVoteService;
    }

    /**
     * Sets the pending rewards service for item rewards.
     * Called after services are initialized to avoid circular dependency.
     */
    public void setPendingRewardsService(PendingRewardsService pendingRewardsService) {
        this.pendingRewardsService = pendingRewardsService;
    }

    /**
     * Sets the vote reminder service for stopping reminders after votes.
     * Called after both services are initialized to avoid circular dependency.
     */
    public void setVoteReminderService(VoteReminderService voteReminderService) {
        this.voteReminderService = voteReminderService;
    }

    public void handleVote(Vote vote) {
        String username = vote.getUsername();

        Optional<PlayerRef> playerOpt = findOnlinePlayer(username);

        if (playerOpt.isPresent()) {
            PlayerRef player = playerOpt.get();
            processVoteRewards(player, vote);
            pendingVoteService.updateLastVoteTime(username);

            // Stop vote reminders since player has now voted
            if (voteReminderService != null) {
                voteReminderService.stopReminder(username);
            }

            plugin.log(Level.INFO, "Vote rewards given to " + username);
        } else {
            pendingVoteService.addPendingVote(username, new PendingVote(vote, System.currentTimeMillis()));
            plugin.log(Level.INFO, "Vote saved as pending for offline player: " + username);
        }
    }

    public void processPendingVotes(PlayerRef player) {
        String username = player.getUsername();
        var pendingVotes = pendingVoteService.getPendingVotes(username);

        if (pendingVotes.isEmpty()) {
            return;
        }

        plugin.log(Level.INFO, "Processing " + pendingVotes.size() + " pending votes for " + username);

        for (PendingVote pending : pendingVotes) {
            processVoteRewards(player, pending.getVote());
        }

        pendingVoteService.clearPendingVotes(username);
        pendingVoteService.updateLastVoteTime(username);

        // Stop vote reminders since player has now voted
        if (voteReminderService != null) {
            voteReminderService.stopReminder(username);
        }
    }

    private void processVoteRewards(PlayerRef player, Vote vote) {
        String username = vote.getUsername();
        String serviceName = vote.getServiceName();

        // Rewards zuerst: Ein Fehler in Chat/Broadcast darf sie nie verhindern.
        // Jeder Schritt einzeln abgesichert; Throwable, damit auch Linkage-Fehler
        // (NoSuchMethodError nach Server-Updates) nur diesen Schritt treffen.
        runStep("Reward-Commands", username, () -> executeRewardCommands(username, serviceName));
        runStep("Item-Rewards", username, () -> addItemRewardsToPending(player, username));

        runStep("Erfolgsnachricht", username, () -> {
            String successMessage = config.getMessages().getVoteSuccess();
            if (!successMessage.isBlank()) {
                player.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw(successMessage).color("#22c55e")));
            }
        });

        if (config.getBroadcast().isEnabled()) {
            runStep("Broadcast", username, () -> sendBroadcast(username, serviceName));
        }
    }

    private void runStep(String name, String username, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            plugin.log(Level.SEVERE, "Vote-Schritt '" + name + "' fehlgeschlagen fuer " + username
                + ": " + t + " (Server-API geaendert? Plugin aktualisieren)");
        }
    }
    /**
     * Adds item rewards to pending rewards service and notifies the player.
     */
    private void addItemRewardsToPending(PlayerRef player, String username) {
        var itemRewards = config.getRewards().getItems();

        // Debug logging
        if (config.isDebug()) {
            plugin.log(Level.INFO, "[DEBUG] addItemRewardsToPending called for " + username);
            plugin.log(Level.INFO, "[DEBUG] itemRewards count: " + (itemRewards != null ? itemRewards.size() : "null"));
            plugin.log(Level.INFO, "[DEBUG] pendingRewardsService: " + (pendingRewardsService != null ? "OK" : "NULL"));
        }

        if (pendingRewardsService == null) {
            plugin.log(Level.WARNING, "PendingRewardsService is null - cannot add item rewards!");
            return;
        }

        if (itemRewards == null || itemRewards.isEmpty()) {
            if (config.isDebug()) {
                plugin.log(Level.INFO, "[DEBUG] No item rewards configured in config.json");
            }
            return;
        }

        // Convert config items to RewardItem objects
        for (HytaleHubConfig.ItemReward configItem : itemRewards) {
            RewardItem reward = RewardItem.fromVote(configItem.getItemId(), configItem.getQuantity());
            pendingRewardsService.addReward(username, reward);

            if (config.isDebug()) {
                plugin.log(Level.INFO, "[DEBUG] Added reward: " + configItem.getItemId() + " x" + configItem.getQuantity());
            }
        }

        // Verify rewards were actually added
        int pendingCount = pendingRewardsService.getRewardCount(username);
        if (config.isDebug()) {
            plugin.log(Level.INFO, "[DEBUG] Total pending rewards for " + username + ": " + pendingCount);
        }

        // Notify player about available rewards
        player.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Du hast Item-Rewards erhalten! ").color("#22c55e"),
                Message.raw("/rewards").color("#58a6ff").bold(true),
                Message.raw(" um sie abzuholen.").color("#22c55e")));

        plugin.log(Level.INFO, "Added " + itemRewards.size() + " item rewards to pending for " + username + " (total pending: " + pendingCount + ")");
    }

    private void sendBroadcast(String username, String serviceName) {
        String template = config.getBroadcast().getMessage();
        String rewardDescription = config.getRewards().getDescription();

        String formatted = template
                .replace("%player%", username)
                .replace("%from%", serviceName)
                .replace("%rewards%", rewardDescription);

        Message msg = Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw(formatted).color("#22c55e"));

        try {
            for (PlayerRef player : Universe.get().getPlayers()) {
                player.sendMessage(msg);
            }
        } catch (Throwable e) {
            plugin.log(Level.WARNING, "Broadcast error: " + e);
        }
    }

    private void executeRewardCommands(String username, String serviceName) {
        for (String command : config.getRewards().getCommands()) {
            String formatted = command
                    .replace("%player%", username)
                    .replace("%from%", serviceName);

            try {
                dispatchCommand(formatted);
                plugin.log(Level.FINE, "Executed reward command: " + formatted);
            } catch (Exception e) {
                plugin.log(Level.WARNING, "Failed to execute command '" + formatted + "': " + e.getMessage());
            }
        }
    }

    private void dispatchCommand(String command) {
        try {
            CommandManager.get().handleCommand(ConsoleSender.INSTANCE, command);
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Command dispatch failed for: " + command + " - " + e.getMessage());
        }
    }

    private Optional<PlayerRef> findOnlinePlayer(String username) {
        try {
            PlayerRef player = Universe.get().getPlayerByUsername(username, NameMatching.EXACT_IGNORE_CASE);
            return Optional.ofNullable(player);
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error finding player: " + e.getMessage());
        }
        return Optional.empty();
    }
}
