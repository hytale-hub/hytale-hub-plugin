package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import de.hytalehub.model.PendingVote;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing pending votes for offline players
 * and tracking last vote timestamps for reminders.
 * 
 * Data is persisted to JSON files to survive server restarts.
 */
public class PendingVoteService {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    private final Path storagePath;
    
    // Username (lowercase) -> List of pending votes
    private final Map<String, List<PendingVote>> pendingVotes;
    
    // Username (lowercase) -> Last vote timestamp (epoch millis)
    private final Map<String, Long> lastVoteTimes;

    public PendingVoteService(Path storagePath) {
        this.storagePath = storagePath;
        this.pendingVotes = new ConcurrentHashMap<>();
        this.lastVoteTimes = new ConcurrentHashMap<>();
    }

    /**
     * Loads pending votes from file.
     */
    public void loadFromFile() throws IOException {
        if (!Files.exists(storagePath)) {
            return;
        }
        
        String json = Files.readString(storagePath, StandardCharsets.UTF_8);
        StorageData data = GSON.fromJson(json, StorageData.class);
        
        if (data != null) {
            if (data.pendingVotes != null) {
                pendingVotes.putAll(data.pendingVotes);
            }
            if (data.lastVoteTimes != null) {
                lastVoteTimes.putAll(data.lastVoteTimes);
            }
        }
    }

    /**
     * Saves pending votes to file.
     */
    public void saveToFile() throws IOException {
        StorageData data = new StorageData();
        data.pendingVotes = new HashMap<>(pendingVotes);
        data.lastVoteTimes = new HashMap<>(lastVoteTimes);
        
        String json = GSON.toJson(data);
        
        // Ensure parent directory exists
        Files.createDirectories(storagePath.getParent());
        Files.writeString(storagePath, json, StandardCharsets.UTF_8);
    }

    /**
     * Adds a pending vote for a player.
     */
    public void addPendingVote(String username, PendingVote vote) {
        String key = username.toLowerCase();
        pendingVotes.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
            .add(vote);
        
        // Auto-save after modification
        try {
            saveToFile();
        } catch (IOException e) {
            // Log but don't fail - in-memory data is still valid
        }
    }

    /**
     * Gets all pending votes for a player.
     */
    public List<PendingVote> getPendingVotes(String username) {
        String key = username.toLowerCase();
        List<PendingVote> votes = pendingVotes.get(key);
        return votes != null ? new ArrayList<>(votes) : Collections.emptyList();
    }

    /**
     * Clears pending votes for a player (after processing).
     */
    public void clearPendingVotes(String username) {
        String key = username.toLowerCase();
        pendingVotes.remove(key);
        
        // Auto-save after modification
        try {
            saveToFile();
        } catch (IOException e) {
            // Log but don't fail
        }
    }

    /**
     * Updates the last vote timestamp for a player.
     */
    public void updateLastVoteTime(String username) {
        String key = username.toLowerCase();
        lastVoteTimes.put(key, System.currentTimeMillis());
        
        // Auto-save
        try {
            saveToFile();
        } catch (IOException e) {
            // Log but don't fail
        }
    }

    /**
     * Gets the last vote timestamp for a player.
     * Returns 0 if player has never voted.
     */
    public long getLastVoteTime(String username) {
        String key = username.toLowerCase();
        return lastVoteTimes.getOrDefault(key, 0L);
    }

    /**
     * Checks if a player should receive a vote reminder.
     * Returns true if lastVoteTime is older than the specified hours.
     */
    public boolean shouldRemind(String username, int reminderIntervalHours) {
        long lastVote = getLastVoteTime(username);
        if (lastVote == 0) {
            // Never voted - should remind
            return true;
        }

        long hoursSinceVote = (System.currentTimeMillis() - lastVote) / (1000 * 60 * 60);
        return hoursSinceVote >= reminderIntervalHours;
    }

    /**
     * Checks if a player has voted today (calendar day check).
     * Returns true if the last vote was on the current calendar day.
     */
    public boolean hasVotedToday(String username) {
        long lastVote = getLastVoteTime(username);
        if (lastVote == 0) {
            // Never voted
            return false;
        }

        LocalDate today = LocalDate.now();
        LocalDate voteDay = Instant.ofEpochMilli(lastVote)
                .atZone(ZoneId.systemDefault())
                .toLocalDate();

        return today.equals(voteDay);
    }

    /**
     * Returns the number of pending votes across all players.
     */
    public int getTotalPendingCount() {
        return pendingVotes.values().stream()
            .mapToInt(List::size)
            .sum();
    }

    /**
     * Internal storage data structure.
     */
    private static class StorageData {
        Map<String, List<PendingVote>> pendingVotes;
        Map<String, Long> lastVoteTimes;
    }
}
