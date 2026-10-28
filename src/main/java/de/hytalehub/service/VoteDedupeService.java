package de.hytalehub.service;

import de.hytalehub.model.Vote;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service to prevent duplicate votes.
 * 
 * Dedupe Key: serviceName + username + date (UTC)
 * A user can only vote once per day per service.
 */
public class VoteDedupeService {

    // Key format: "serviceName:username:date" -> timestamp of last vote
    private final Map<String, Long> voteHistory = new ConcurrentHashMap<>();
    
    // Cleanup old entries after this many hours
    private static final int CLEANUP_HOURS = 48;

    /**
     * Checks if a vote is a duplicate.
     * 
     * @param vote The vote to check
     * @return true if this is a duplicate vote (should be rejected)
     */
    public boolean isDuplicate(Vote vote) {
        String key = buildKey(vote);
        Long lastVote = voteHistory.get(key);
        
        if (lastVote == null) {
            return false;
        }
        
        // Same key exists = duplicate for this day
        return true;
    }

    /**
     * Records a vote as processed.
     * Call this AFTER successfully processing the vote.
     * 
     * @param vote The vote that was processed
     */
    public void recordVote(Vote vote) {
        String key = buildKey(vote);
        voteHistory.put(key, System.currentTimeMillis());
        
        // Cleanup old entries periodically
        cleanupOldEntries();
    }

    /**
     * Checks if vote is duplicate and records it if not.
     * 
     * @param vote The vote to check and record
     * @return true if this is a duplicate (should be rejected), false if new (was recorded)
     */
    public boolean checkAndRecord(Vote vote) {
        String key = buildKey(vote);
        
        synchronized (voteHistory) {
            if (voteHistory.containsKey(key)) {
                return true; // Duplicate
            }
            voteHistory.put(key, System.currentTimeMillis());
            return false; // New vote, recorded
        }
    }

    /**
     * Builds a unique key for deduplication.
     * Format: "serviceName:username:YYYY-MM-DD"
     */
    private String buildKey(Vote vote) {
        LocalDate voteDate;
        
        if (vote.getTimestamp() > 0) {
            // Use vote timestamp if provided
            voteDate = Instant.ofEpochSecond(vote.getTimestamp())
                .atZone(ZoneId.of("UTC"))
                .toLocalDate();
        } else {
            // Use current date as fallback
            voteDate = LocalDate.now(ZoneId.of("UTC"));
        }
        
        return vote.getServiceName() + ":" + 
               vote.getUsername().toLowerCase() + ":" + 
               voteDate.toString();
    }

    /**
     * Removes entries older than CLEANUP_HOURS.
     */
    private void cleanupOldEntries() {
        long cutoff = System.currentTimeMillis() - (CLEANUP_HOURS * 60 * 60 * 1000L);
        voteHistory.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * Gets the number of tracked votes (for debugging).
     */
    public int getTrackedVoteCount() {
        return voteHistory.size();
    }

    /**
     * Clears all tracked votes (for testing).
     */
    public void clear() {
        voteHistory.clear();
    }
}
