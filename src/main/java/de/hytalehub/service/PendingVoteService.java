package de.hytalehub.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.model.PendingVote;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.logging.Level;

/**
 * Service for managing pending votes for offline players
 * and tracking last vote timestamps for reminders.
 *
 * Data is persisted to JSON files to survive server restarts.
 * Thread-safe: all access goes through synchronized methods; callers only get copies.
 */
public class PendingVoteService {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    /** How many delivered vote ids are remembered to drop repeated deliveries. */
    private static final int SEEN_VOTE_IDS_LIMIT = 1000;

    private final Path storagePath;

    // Username (lowercase) -> List of pending votes
    private final Map<String, List<PendingVote>> pendingVotes = new HashMap<>();

    // Username (lowercase) -> Last vote timestamp (epoch millis)
    private final Map<String, Long> lastVoteTimes = new HashMap<>();

    // Ids of votes already received from the pull API, oldest first
    private final LinkedHashSet<String> seenVoteIds = new LinkedHashSet<>();

    public PendingVoteService(Path storagePath) {
        this.storagePath = storagePath;
    }

    /**
     * Loads pending votes from file. A corrupt file is moved aside (see {@link JsonFiles}).
     */
    public synchronized void loadFromFile() throws IOException {
        StorageData data = JsonFiles.read(storagePath, json -> GSON.fromJson(json, StorageData.class));
        if (data == null) {
            return;
        }
        if (data.pendingVotes != null) {
            data.pendingVotes.forEach((user, votes) -> {
                if (user != null && votes != null && !votes.isEmpty()) {
                    pendingVotes.put(user.toLowerCase(), new ArrayList<>(votes));
                }
            });
        }
        if (data.lastVoteTimes != null) {
            lastVoteTimes.putAll(data.lastVoteTimes);
        }
        if (data.seenVoteIds != null) {
            seenVoteIds.addAll(data.seenVoteIds);
        }
    }

    /**
     * Saves pending votes to file (atomic).
     */
    public synchronized void saveToFile() throws IOException {
        StorageData data = new StorageData();
        data.pendingVotes = new HashMap<>();
        pendingVotes.forEach((user, votes) -> data.pendingVotes.put(user, new ArrayList<>(votes)));
        data.lastVoteTimes = new HashMap<>(lastVoteTimes);
        data.seenVoteIds = new ArrayList<>(seenVoteIds);
        JsonFiles.writeAtomic(storagePath, GSON.toJson(data));
    }

    /**
     * Remembers the id of a delivered vote.
     *
     * @return true if the id is new, false if this vote was already received (drop it)
     */
    public synchronized boolean markVoteIdSeen(String voteId) {
        if (voteId == null || voteId.isBlank()) {
            return true;
        }
        if (!seenVoteIds.add(voteId)) {
            return false;
        }
        while (seenVoteIds.size() > SEEN_VOTE_IDS_LIMIT) {
            Iterator<String> it = seenVoteIds.iterator();
            it.next();
            it.remove();
        }
        trySave();
        return true;
    }

    /**
     * Adds a pending vote for a player.
     */
    public synchronized void addPendingVote(String username, PendingVote vote) {
        pendingVotes.computeIfAbsent(key(username), k -> new ArrayList<>()).add(vote);
        trySave();
    }

    /**
     * Gets all pending votes for a player (a copy).
     */
    public synchronized List<PendingVote> getPendingVotes(String username) {
        List<PendingVote> votes = pendingVotes.get(key(username));
        return votes != null ? new ArrayList<>(votes) : new ArrayList<>();
    }

    /**
     * Removes and returns all pending votes for a player in one step, so a vote that
     * arrives while they are processed is not deleted unseen.
     */
    public synchronized List<PendingVote> takePendingVotes(String username) {
        List<PendingVote> votes = pendingVotes.remove(key(username));
        if (votes == null) {
            return new ArrayList<>();
        }
        trySave();
        return votes;
    }

    /**
     * Clears pending votes for a player (after processing).
     */
    public synchronized void clearPendingVotes(String username) {
        pendingVotes.remove(key(username));
        trySave();
    }

    /**
     * Updates the last vote timestamp for a player.
     */
    public synchronized void updateLastVoteTime(String username) {
        lastVoteTimes.put(key(username), System.currentTimeMillis());
        trySave();
    }

    /**
     * Gets the last vote timestamp for a player.
     * Returns 0 if player has never voted.
     */
    public synchronized long getLastVoteTime(String username) {
        return lastVoteTimes.getOrDefault(key(username), 0L);
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
    public synchronized int getTotalPendingCount() {
        return pendingVotes.values().stream()
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
            HytaleHubPlugin plugin = HytaleHubPlugin.getInstance();
            if (plugin != null) {
                plugin.log(Level.WARNING, "Could not save pending votes: " + e.getMessage());
            }
        }
    }

    /**
     * Internal storage data structure.
     */
    private static class StorageData {
        Map<String, List<PendingVote>> pendingVotes;
        Map<String, Long> lastVoteTimes;
        List<String> seenVoteIds;
    }
}
