package de.hytalehub.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.command.system.arguments.system.DefaultArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.service.RulesAcceptanceService;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * /resetrules [player] command - Resets rule acceptance for all or a specific player.
 * Admin-only (uses auto-generated permission from CommandBase).
 */
public class ResetRulesCommand extends CommandBase {

    private final HytaleHubPlugin plugin;
    private final RulesAcceptanceService rulesAcceptanceService;
    private final DefaultArg<String> playerArg;

    public ResetRulesCommand(HytaleHubPlugin plugin, RulesAcceptanceService rulesAcceptanceService, String commandName) {
        super(commandName, "hytalehub.command.resetrules.desc");

        this.plugin = plugin;
        this.rulesAcceptanceService = rulesAcceptanceService;

        this.playerArg = withDefaultArg("player", "Player name (optional, resets all if omitted)", ArgTypes.STRING, "", "");
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        String playerName = context.get(playerArg);

        if (playerName == null || playerName.trim().isEmpty()) {
            // Reset all players
            int count = rulesAcceptanceService.resetAll();
            context.sendMessage(Message.raw("[HytaleHub] " + count + " Spieler muessen die Regeln erneut akzeptieren."));
            plugin.log(Level.INFO, "All rule acceptances reset (" + count + " players) by admin");
        } else {
            // Reset specific player
            playerName = playerName.trim();
            boolean removed = rulesAcceptanceService.resetPlayer(playerName);
            if (removed) {
                context.sendMessage(Message.raw("[HytaleHub] Regeln fuer " + playerName + " zurueckgesetzt."));
                plugin.log(Level.INFO, "Rule acceptance reset for player: " + playerName);
            } else {
                context.sendMessage(Message.raw("[HytaleHub] " + playerName + " hat die Regeln noch nicht akzeptiert."));
            }
        }
    }
}
