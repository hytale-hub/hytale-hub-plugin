package de.hytalehub.listener;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.ui.RulesPage;
import de.hytalehub.service.PendingRewardsService;
import de.hytalehub.service.RulesAcceptanceService;
import de.hytalehub.service.VoteReminderService;

import java.util.logging.Level;

/**
 * Listener for player connect events.
 * Handles pending votes and starts vote reminders.
 */
public class PlayerJoinListener {

    private final HytaleHubPlugin plugin;
    private final VoteHandler voteHandler;
    private final VoteReminderService voteReminderService;
    private final PendingRewardsService pendingRewardsService;
    private final RulesAcceptanceService rulesAcceptanceService;

    public PlayerJoinListener(
            HytaleHubPlugin plugin,
            VoteHandler voteHandler,
            VoteReminderService voteReminderService,
            PendingRewardsService pendingRewardsService,
            RulesAcceptanceService rulesAcceptanceService) {
        this.plugin = plugin;
        this.voteHandler = voteHandler;
        this.voteReminderService = voteReminderService;
        this.pendingRewardsService = pendingRewardsService;
        this.rulesAcceptanceService = rulesAcceptanceService;
    }

    public void onPlayerJoin(PlayerConnectEvent event) {
        PlayerRef player = event.getPlayerRef();

        if (player == null) {
            return;
        }

        // If first-join rules are enabled and player hasn't accepted yet, defer to onPlayerReady
        if (needsRulesAcceptance(player)) {
            return;
        }

        runNormalJoinFlow(player);
    }

    public void onPlayerReady(PlayerReadyEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        PlayerRef playerRef = player.getPlayerRef();
        if (playerRef == null) {
            return;
        }

        // Only show rules page if needed
        if (!needsRulesAcceptance(playerRef)) {
            return;
        }

        plugin.log(Level.INFO, "Showing rules page to " + playerRef.getUsername());

        try {
            Ref<EntityStore> ref = player.getReference();
            Store<EntityStore> store = player.getWorld().getEntityStore().getStore();

            // onAccepted callback: run normal join flow after acceptance
            Runnable onAccepted = () -> runNormalJoinFlow(playerRef);

            player.getPageManager().openCustomPage(ref, store,
                new RulesPage(playerRef, plugin.getRulesConfig(), rulesAcceptanceService, onAccepted));
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Failed to open rules page for " + playerRef.getUsername() + ": " + e.getMessage());
        }
    }

    private boolean needsRulesAcceptance(PlayerRef player) {
        if (rulesAcceptanceService == null) {
            return false;
        }
        if (!plugin.getRulesConfig().isEnabled()) {
            return false;
        }
        return !rulesAcceptanceService.hasAccepted(player.getUsername());
    }

    private void runNormalJoinFlow(PlayerRef player) {
        processPendingVotes(player);
        notifyPendingRewards(player);

        // Start periodic vote reminder (if player hasn't voted today)
        if (voteReminderService != null) {
            voteReminderService.startReminder(player);
        }
    }

    /**
     * Notifies the player if they have pending item rewards to claim.
     */
    private void notifyPendingRewards(PlayerRef player) {
        if (pendingRewardsService == null) {
            return;
        }

        try {
            String username = player.getUsername();
            if (pendingRewardsService.hasRewards(username)) {
                int count = pendingRewardsService.getRewardCount(username);

                player.sendMessage(Message.join(
                        Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                        Message.raw("Du hast ").color("#22c55e"),
                        Message.raw(count + " ausstehende Rewards").color("#fbbf24").bold(true),
                        Message.raw("! Hole sie ab mit ").color("#22c55e"),
                        Message.raw("/rewards").color("#58a6ff").bold(true)));
            }
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error checking pending rewards for " + player.getUsername() + ": " + e.getMessage());
        }
    }

    private void processPendingVotes(PlayerRef player) {
        try {
            voteHandler.processPendingVotes(player);
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error processing pending votes for " + player.getUsername() + ": " + e.getMessage());
        }
    }
}
