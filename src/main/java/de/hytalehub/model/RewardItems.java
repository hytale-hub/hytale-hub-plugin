package de.hytalehub.model;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;

/**
 * Checks reward items against the server's item registry.
 *
 * An unknown item id does not fail on the server: it silently becomes an "unknown" item,
 * so the player would receive nothing useful and the reward would be used up.
 */
public final class RewardItems {

    private RewardItems() {
    }

    public static boolean isUsable(String itemId, int quantity) {
        if (itemId == null || itemId.isBlank() || quantity <= 0) {
            return false;
        }
        try {
            return Item.getAssetMap().getAsset(itemId) != null;
        } catch (Throwable e) {
            // Registry not available (e.g. API changed): do not block rewards because of the check
            return true;
        }
    }

    public static boolean isUsable(RewardItem reward) {
        return reward != null && isUsable(reward.getItemId(), reward.getQuantity());
    }
}
