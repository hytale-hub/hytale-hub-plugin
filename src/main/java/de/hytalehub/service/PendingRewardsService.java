package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.model.RewardItem;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Service for managing pending item rewards for players.
 * Rewards are stored persistently and can be claimed via /rewards command.
 *
 * Thread-safe: votes arrive on poll/TCP/HTTP threads while claims run on the world thread.
 * All access goes through synchronized methods; callers only ever get copies.
 */
public class PendingRewardsService {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private final Path storagePath;

    // Username (lowercase) -> List of pending reward items
    private final Map<String, List<RewardItem>> pendingRewards = new HashMap<>();

    public PendingRewardsService(Path storagePath) {
        this.storagePath = storagePath;
    }

    /**
     * Loads pending rewards from file. A corrupt file is moved aside (see {@link JsonFiles}).
     */
    public synchronized void loadFromFile() throws IOException {
        StorageData data = JsonFiles.read(storagePath, json -> GSON.fromJson(json, StorageData.class));
        if (data == null || data.pendingRewards == null) {
            return;
        }
        data.pendingRewards.forEach((user, items) -> {
            if (user != null && items != null && !items.isEmpty()) {
                pendingRewards.put(user.toLowerCase(), new ArrayList<>(items));
            }
        });
    }

    /**
     * Saves pending rewards to file (atomic).
     */
    public synchronized void saveToFile() throws IOException {
        StorageData data = new StorageData();
        data.pendingRewards = new HashMap<>();
        pendingRewards.forEach((user, items) -> data.pendingRewards.put(user, new ArrayList<>(items)));
        JsonFiles.writeAtomic(storagePath, GSON.toJson(data));
    }

    /**
     * Adds a single reward item for a player.
     */
    public synchronized void addReward(String username, RewardItem item) {
        pendingRewards.computeIfAbsent(key(username), k -> new ArrayList<>()).add(item);
        trySave();
    }

    /**
     * Adds multiple reward items for a player.
     */
    public synchronized void addRewards(String username, List<RewardItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        pendingRewards.computeIfAbsent(key(username), k -> new ArrayList<>()).addAll(items);
        trySave();
    }

    /**
     * Gets all pending rewards for a player (a copy).
     */
    public synchronized List<RewardItem> getRewards(String username) {
        List<RewardItem> rewards = pendingRewards.get(key(username));
        return rewards != null ? new ArrayList<>(rewards) : new ArrayList<>();
    }

    /**
     * Removes one reward (the first equal entry) after it was handed out.
     * Rewards added in the meantime stay untouched.
     *
     * @return true if the reward was still pending and has been removed
     */
    public synchronized boolean removeReward(String username, RewardItem item) {
        String key = key(username);
        List<RewardItem> rewards = pendingRewards.get(key);
        if (rewards == null || !rewards.remove(item)) {
            return false;
        }
        if (rewards.isEmpty()) {
            pendingRewards.remove(key);
        }
        trySave();
        return true;
    }

    /**
     * Replaces all rewards for a player.
     * Only safe when nothing else can add rewards for this player at the same time;
     * prefer {@link #removeReward(String, RewardItem)}.
     */
    public synchronized void replaceRewards(String username, List<RewardItem> remaining) {
        String key = key(username);
        if (remaining == null || remaining.isEmpty()) {
            pendingRewards.remove(key);
        } else {
            pendingRewards.put(key, new ArrayList<>(remaining));
        }
        trySave();
    }

    /**
     * Clears all pending rewards for a player (after claiming).
     */
    public synchronized void clearRewards(String username) {
        pendingRewards.remove(key(username));
        trySave();
    }

    /**
     * Checks if a player has pending rewards.
     */
    public synchronized boolean hasRewards(String username) {
        List<RewardItem> rewards = pendingRewards.get(key(username));
        return rewards != null && !rewards.isEmpty();
    }

    /**
     * Returns the number of pending reward items for a player.
     */
    public synchronized int getRewardCount(String username) {
        List<RewardItem> rewards = pendingRewards.get(key(username));
        return rewards != null ? rewards.size() : 0;
    }

    /**
     * Returns the total number of pending rewards across all players.
     */
    public synchronized int getTotalPendingCount() {
        return pendingRewards.values().stream()
                .mapToInt(List::size)
                .sum();
    }

    private static String key(String username) {
        return username.toLowerCase();
    }

    private void trySave() {
        try {
            saveToFile();
        } catch (IOException e) {
            // In-memory data is still valid; log so a full disk does not go unnoticed
            HytaleHubPlugin plugin = HytaleHubPlugin.getInstance();
            if (plugin != null) {
                plugin.log(Level.WARNING, "Could not save pending rewards: " + e.getMessage());
            }
        }
    }

    /**
     * Internal storage data structure.
     */
    private static class StorageData {
        Map<String, List<RewardItem>> pendingRewards;
    }
}
