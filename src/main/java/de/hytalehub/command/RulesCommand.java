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
import de.hytalehub.config.RulesConfig;
import de.hytalehub.ui.ViewRulesPage;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * /rules command - Opens a read-only view of the server rules.
 */
public class RulesCommand extends AbstractPlayerCommand {

    private final HytaleHubPlugin plugin;
    private final RulesConfig rulesConfig;

    public RulesCommand(HytaleHubPlugin plugin, RulesConfig rulesConfig, String commandName) {
        super(commandName, "Zeige die Serverregeln an", false);

        // Ab Server 0.6 braucht jeder Befehl sonst eine Permission; vorher war es canGeneratePermission()=false
        try {
            requireNoPermission();
        } catch (Throwable ignored) {
            // aeltere Server-API: canGeneratePermission() greift
        }
        this.plugin = plugin;
        this.rulesConfig = rulesConfig;
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

        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null) {
                ctx.sendMessage(Message.join(
                    Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                    Message.raw("Fehler: Spieler-Entity nicht gefunden.").color("#ef4444")
                ));
                return;
            }

            player.getPageManager().openCustomPage(ref, store, new ViewRulesPage(playerRef, rulesConfig));
        } catch (Exception e) {
            plugin.log(Level.WARNING, "Error opening rules page for " + playerRef.getUsername() + ": " + e.getMessage());
            ctx.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Ein Fehler ist aufgetreten.").color("#ef4444")
            ));
        }
    }
}
