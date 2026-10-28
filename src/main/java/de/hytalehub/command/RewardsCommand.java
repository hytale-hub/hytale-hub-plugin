package de.hytalehub.command;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.model.RewardItem;
import de.hytalehub.service.PendingRewardsService;
import de.hytalehub.ui.RewardsPage;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

/**
 * /rewards command - Opens an interactive UI where players can claim their pending rewards.
 *
 * Features:
 * - Pagination for large reward lists (unlimited rewards supported)
 * - Individual claim buttons per item
 * - "Claim All" button
 * - Anti-duplication protection (only one page per player)
 * - Items go directly into player inventory
 */
public class RewardsCommand extends AbstractPlayerCommand {

    private final HytaleHubPlugin plugin;
    private final PendingRewardsService pendingRewardsService;

    public RewardsCommand(HytaleHubPlugin plugin, PendingRewardsService pendingRewardsService) {
        super("rewards", "Hole deine ausstehenden Rewards ab", false);

        // Ab Server 0.6 braucht jeder Befehl sonst eine Permission; vorher war es canGeneratePermission()=false
        try {
            requireNoPermission();
        } catch (Throwable ignored) {
            // aeltere Server-API: canGeneratePermission() greift
        }
        this.plugin = plugin;
        this.pendingRewardsService = pendingRewardsService;
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
        List<RewardItem> rewards = pendingRewardsService.getRewards(username);

        plugin.log(Level.INFO, "/rewards called by " + username + " - pending rewards: " + rewards.size());

        // Check if player has any pending rewards
        if (rewards.isEmpty()) {
            ctx.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Du hast keine ausstehenden Rewards.").color("#888888")
            ));
            return;
        }

        // ANTI-DUPLICATION: Check if player already has an open rewards page
        if (RewardsPage.hasActivePage(username)) {
            ctx.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Du hast bereits ein Rewards-Fenster offen!").color("#fbbf24")
            ));
            plugin.log(Level.INFO, username + " tried to open second rewards page - blocked");
            return;
        }

        try {
            // Get Player entity
            Player player = store.getComponent(ref, Player.getComponentType());

            if (player == null) {
                ctx.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw("Fehler: Spieler-Entity nicht gefunden.").color("#ef4444")
                ));
                plugin.log(Level.WARNING, "Could not get Player entity for " + username);
                return;
            }

            // Create RewardsPage with pagination support
            RewardsPage page = new RewardsPage(
                playerRef,
                username,
                rewards,
                pendingRewardsService
            );

            // Open the custom UI page
            player.getPageManager().openCustomPage(ref, store, page);

            plugin.log(Level.INFO, username + " opened rewards page with " + rewards.size() + " item(s)");

        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error opening rewards page for " + username + ": " + e.getMessage());
            e.printStackTrace();
            ctx.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Ein Fehler ist aufgetreten. Versuche es spaeter erneut.").color("#ef4444")
            ));
        }
    }
}
