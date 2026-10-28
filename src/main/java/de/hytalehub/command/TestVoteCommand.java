package de.hytalehub.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.handler.VoteHandler;
import de.hytalehub.model.Vote;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * /testvote <player> command - Simulates a vote for testing.
 * 
 * API classes verified from HytaleServer.jar:
 * - com.hypixel.hytale.server.core.command.system.basecommands.CommandBase
 * - AbstractCommand.withRequiredArg(String name, String desc, ArgumentType<D>)
 */
public class TestVoteCommand extends CommandBase {

    private final HytaleHubPlugin plugin;
    private final VoteHandler voteHandler;
    private final RequiredArg<String> playerArg;

    public TestVoteCommand(HytaleHubPlugin plugin, VoteHandler voteHandler, String commandName) {
        super(commandName, "hytalehub.command.testvote.desc");
        
        this.plugin = plugin;
        this.voteHandler = voteHandler;
        
        // Use withRequiredArg() method from AbstractCommand
        this.playerArg = withRequiredArg("player", "Player name", ArgTypes.STRING);
    }

    @Override
    protected void executeSync(@Nonnull CommandContext context) {
        String playerName = context.get(playerArg);
        
        if (playerName == null || playerName.trim().isEmpty()) {
            context.sendMessage(Message.raw("[HytaleHub] Verwendung: /testvote <spielername>"));
            return;
        }
        
        playerName = playerName.trim();
        
        Vote testVote = new Vote(
            "HytaleHub",
            playerName,
            System.currentTimeMillis() / 1000
        );
        
        try {
            voteHandler.handleVote(testVote);
            context.sendMessage(Message.raw("[HytaleHub] Test-Vote für " + playerName + " wurde verarbeitet!"));
            plugin.log(Level.INFO, "Test vote triggered by admin for player: " + playerName);
        } catch (Exception e) {
            context.sendMessage(Message.raw("[HytaleHub] Fehler: " + e.getMessage()));
            plugin.log(Level.WARNING, "Test vote error: " + e.getMessage());
        }
    }
}
