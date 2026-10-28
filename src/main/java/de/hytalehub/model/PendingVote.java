package de.hytalehub.model;

import com.google.gson.annotations.SerializedName;

/**
 * Represents a pending vote for an offline player.
 * Will be processed when the player joins.
 */
public class PendingVote {

    @SerializedName("vote")
    private Vote vote;
    
    @SerializedName("receivedAt")
    private long receivedAt;

    public PendingVote(Vote vote, long receivedAt) {
        this.vote = vote;
        this.receivedAt = receivedAt;
    }

    public Vote getVote() {
        return vote;
    }

    public long getReceivedAt() {
        return receivedAt;
    }

    @Override
    public String toString() {
        return "PendingVote{" +
            "vote=" + vote +
            ", receivedAt=" + receivedAt +
            '}';
    }
}
