package de.hytalehub.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Separate configuration file for first-join rules and commands.
 * Stored as rules_config.json in the plugin data directory.
 */
public class RulesConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private boolean enabled = false;
    private int acceptDelaySeconds = 5;
    private String declineKickMessage = "Du musst die Regeln akzeptieren um spielen zu koennen.";
    private boolean resetRulesCommand = true;
    private String resetRulesCommandName = "resetrules";
    private boolean rulesCommand = true;
    private String rulesCommandName = "rules";
    private boolean commandsCommand = true;
    private String commandsCommandName = "commands";

    private List<String> rules = List.of(
        "1. Respektiere alle Spieler",
        "2. Kein Cheating oder Exploiting",
        "3. Kein Spam im Chat",
        "4. Keine unangemessenen Bauwerke",
        "5. Folge den Anweisungen der Moderatoren"
    );

    private List<CommandEntry> commands = List.of(
        new CommandEntry("/vote", "Vote fuer den Server und erhalte Belohnungen"),
        new CommandEntry("/rewards", "Hole deine ausstehenden Rewards ab"),
        new CommandEntry("/help", "Zeige alle verfuegbaren Commands")
    );

    public static RulesConfig createDefault() {
        return new RulesConfig();
    }

    public static RulesConfig loadFromFile(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        RulesConfig config = GSON.fromJson(json, RulesConfig.class);
        return config != null ? config : createDefault();
    }

    public void saveToFile(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        String json = GSON.toJson(this);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    // ========== Getters ==========

    public boolean isEnabled() {
        return enabled;
    }

    public int getAcceptDelaySeconds() {
        return Math.max(acceptDelaySeconds, 1);
    }

    public String getDeclineKickMessage() {
        return declineKickMessage != null ? declineKickMessage : "Du musst die Regeln akzeptieren um spielen zu koennen.";
    }

    public List<String> getRules() {
        return rules != null ? rules : List.of();
    }

    public boolean isResetRulesCommand() {
        return resetRulesCommand;
    }

    public String getResetRulesCommandName() {
        if (resetRulesCommandName == null || resetRulesCommandName.isBlank()) {
            return "resetrules";
        }
        return resetRulesCommandName.trim().toLowerCase();
    }

    public boolean isRulesCommand() {
        return rulesCommand;
    }

    public String getRulesCommandName() {
        if (rulesCommandName == null || rulesCommandName.isBlank()) {
            return "rules";
        }
        return rulesCommandName.trim().toLowerCase();
    }

    public boolean isCommandsCommand() {
        return commandsCommand;
    }

    public String getCommandsCommandName() {
        if (commandsCommandName == null || commandsCommandName.isBlank()) {
            return "commands";
        }
        return commandsCommandName.trim().toLowerCase();
    }

    public List<CommandEntry> getCommands() {
        return commands != null ? commands : List.of();
    }

    // ========== Inner class ==========

    public static class CommandEntry {
        private String command;
        private String description;

        public CommandEntry() {
            // Required for Gson
        }

        public CommandEntry(String command, String description) {
            this.command = command;
            this.description = description;
        }

        public String getCommand() {
            return command != null ? command : "";
        }

        public String getDescription() {
            return description != null ? description : "";
        }
    }
}
