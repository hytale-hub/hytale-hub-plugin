package de.hytalehub.ui;

import de.hytalehub.model.RewardItem;
import de.hytalehub.service.PendingRewardsService;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for managing reward item claims.
 *
 * Note: Container-Window API (ContainerWindow, WindowManager.openWindow) is not
 * available in the current Hytale Server API. This class provides utility methods
 * for the command-based fallback approach.
 *
 * TODO: Implement proper Container UI when API becomes available.
 */
public class RewardsWindow {

    private final PendingRewardsService pendingRewardsService;
    private final String username;
    private final List<RewardItem> rewards;

    public RewardsWindow(String username, List<RewardItem> rewards, PendingRewardsService service) {
        this.username = username;
        this.pendingRewardsService = service;
        this.rewards = new ArrayList<>(rewards);
    }

    public String getUsername() {
        return username;
    }

    public List<RewardItem> getRewards() {
        return new ArrayList<>(rewards);
    }

    public int getRewardCount() {
        return rewards.size();
    }

    public void markClaimed(RewardItem item) {
        rewards.remove(item);
        pendingRewardsService.replaceRewards(username, rewards);
    }

    public void markAllClaimed() {
        rewards.clear();
        pendingRewardsService.clearRewards(username);
    }

    public boolean hasRemainingRewards() {
        return !rewards.isEmpty();
    }
}
