package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.hytalehub.model.RewardItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing pending item rewards for players.
 * Rewards are stored persistently and can be claimed via /rewards command.
 */
public class PendingRewardsService {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private final Path storagePath;

    // Username (lowercase) -> List of pending reward items
    private final Map<String, List<RewardItem>> pendingRewards;

    public PendingRewardsService(Path storagePath) {
        this.storagePath = storagePath;
        this.pendingRewards = new ConcurrentHashMap<>();
    }

    /**
     * Loads pending rewards from file.
     */
    public void loadFromFile() throws IOException {
        if (!Files.exists(storagePath)) {
            return;
        }

        String json = Files.readString(storagePath, StandardCharsets.UTF_8);
        StorageData data = GSON.fromJson(json, StorageData.class);

        if (data != null && data.pendingRewards != null) {
            pendingRewards.putAll(data.pendingRewards);
        }
    }

    /**
     * Saves pending rewards to file.
     */
    public void saveToFile() throws IOException {
        StorageData data = new StorageData();
        data.pendingRewards = new HashMap<>(pendingRewards);

        String json = GSON.toJson(data);

        // Ensure parent directory exists
        Files.createDirectories(storagePath.getParent());
        Files.writeString(storagePath, json, StandardCharsets.UTF_8);
    }

    /**
     * Adds a single reward item for a player.
     */
    public void addReward(String username, RewardItem item) {
        String key = username.toLowerCase();
        pendingRewards.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(item);

        // Auto-save after modification
        trySave();
    }

    /**
     * Adds multiple reward items for a player.
     */
    public void addRewards(String username, List<RewardItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }

        String key = username.toLowerCase();
        pendingRewards.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .addAll(items);

        // Auto-save after modification
        trySave();
    }

    /**
     * Gets all pending rewards for a player.
     * Returns a copy of the list.
     */
    public List<RewardItem> getRewards(String username) {
        String key = username.toLowerCase();
        List<RewardItem> rewards = pendingRewards.get(key);
        return rewards != null ? new ArrayList<>(rewards) : Collections.emptyList();
    }

    /**
     * Replaces all rewards for a player.
     * Used when player closes rewards window with items still in container.
     */
    public void replaceRewards(String username, List<RewardItem> remaining) {
        String key = username.toLowerCase();

        if (remaining == null || remaining.isEmpty()) {
            pendingRewards.remove(key);
        } else {
            pendingRewards.put(key, Collections.synchronizedList(new ArrayList<>(remaining)));
        }

        // Auto-save after modification
        trySave();
    }

    /**
     * Clears all pending rewards for a player (after claiming).
     */
    public void clearRewards(String username) {
        String key = username.toLowerCase();
        pendingRewards.remove(key);

        // Auto-save after modification
        trySave();
    }

    /**
     * Checks if a player has pending rewards.
     */
    public boolean hasRewards(String username) {
        String key = username.toLowerCase();
        List<RewardItem> rewards = pendingRewards.get(key);
        return rewards != null && !rewards.isEmpty();
    }

    /**
     * Returns the number of pending reward items for a player.
     */
    public int getRewardCount(String username) {
        String key = username.toLowerCase();
        List<RewardItem> rewards = pendingRewards.get(key);
        return rewards != null ? rewards.size() : 0;
    }

    /**
     * Returns the total number of pending rewards across all players.
     */
    public int getTotalPendingCount() {
        return pendingRewards.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private void trySave() {
        try {
            saveToFile();
        } catch (IOException e) {
            // Log but don't fail - in-memory data is still valid
        }
    }

    /**
     * Internal storage data structure.
     */
    private static class StorageData {
        Map<String, List<RewardItem>> pendingRewards;
    }
}
