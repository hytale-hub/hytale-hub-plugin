package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Tracks which players have accepted the server rules.
 * Persists to accepted_rules.json in the plugin data directory.
 */
public class RulesAcceptanceService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type SET_TYPE = new TypeToken<Set<String>>() {}.getType();

    private final Path filePath;
    private final Set<String> acceptedPlayers = new HashSet<>();

    public RulesAcceptanceService(Path filePath) {
        this.filePath = filePath;
    }

    public void loadFromFile() throws IOException {
        acceptedPlayers.clear();
        if (Files.exists(filePath)) {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            Set<String> loaded = GSON.fromJson(json, SET_TYPE);
            if (loaded != null) {
                acceptedPlayers.addAll(loaded);
            }
        }
    }

    public void saveToFile() throws IOException {
        Files.createDirectories(filePath.getParent());
        String json = GSON.toJson(acceptedPlayers);
        Files.writeString(filePath, json, StandardCharsets.UTF_8);
    }

    public boolean hasAccepted(String username) {
        return acceptedPlayers.contains(username.toLowerCase());
    }

    public void markAccepted(String username) {
        acceptedPlayers.add(username.toLowerCase());
        try {
            saveToFile();
        } catch (IOException e) {
            // Will be saved on shutdown as fallback
        }
    }

    public int resetAll() {
        int count = acceptedPlayers.size();
        acceptedPlayers.clear();
        try {
            saveToFile();
        } catch (IOException e) {
            // Will be saved on shutdown as fallback
        }
        return count;
    }

    public boolean resetPlayer(String username) {
        boolean removed = acceptedPlayers.remove(username.toLowerCase());
        if (removed) {
            try {
                saveToFile();
            } catch (IOException e) {
                // Will be saved on shutdown as fallback
            }
        }
        return removed;
    }

    public int getAcceptedCount() {
        return acceptedPlayers.size();
    }
}
