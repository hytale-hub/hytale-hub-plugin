package de.hytalehub.model;

import java.util.Objects;

/**
 * Represents a pending reward item that can be claimed via /rewards.
 */
public class RewardItem {

    private String itemId;      // e.g., "Ore_Gold", "Diamond"
    private int quantity;
    private long addedAt;       // Timestamp when reward was added
    private String source;      // e.g., "vote", "achievement"

    public RewardItem() {
        // Required for Gson deserialization
    }

    public RewardItem(String itemId, int quantity, long addedAt, String source) {
        this.itemId = itemId;
        this.quantity = quantity;
        this.addedAt = addedAt;
        this.source = source;
    }

    /**
     * Creates a reward item from a vote.
     */
    public static RewardItem fromVote(String itemId, int quantity) {
        return new RewardItem(itemId, quantity, System.currentTimeMillis(), "vote");
    }

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public long getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(long addedAt) {
        this.addedAt = addedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RewardItem that = (RewardItem) o;
        return quantity == that.quantity &&
                addedAt == that.addedAt &&
                Objects.equals(itemId, that.itemId) &&
                Objects.equals(source, that.source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, quantity, addedAt, source);
    }

    @Override
    public String toString() {
        return "RewardItem{" +
                "itemId='" + itemId + '\'' +
                ", quantity=" + quantity +
                ", source='" + source + '\'' +
                '}';
    }
}
