package de.hytalehub.ui;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.model.RewardItem;
import de.hytalehub.service.PendingRewardsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Custom ContainerWindow for reward items.
 *
 * FIXED VERSION using:
 * - FilterType.ALLOW_OUTPUT_ONLY - Server-side blocking of all input actions
 * - Window tracking to prevent multiple windows per player
 * - closeEventRegistry for reliable close handling (not just onClose0)
 *
 * This approach uses the Hytale API's built-in filter system instead of
 * trying to override handleAction() which doesn't work.
 */
public class RewardsContainerWindow extends ContainerWindow {

    // Active windows per player - prevents duplication bug
    private static final Map<String, RewardsContainerWindow> ACTIVE_WINDOWS = new ConcurrentHashMap<>();

    private final String username;
    private final PendingRewardsService pendingRewardsService;
    private final int containerSize;
    private final List<RewardItem> originalRewards;

    // Flag to prevent double-processing
    private volatile boolean processed = false;

    /**
     * Creates a new RewardsContainerWindow.
     *
     * @param username The player's username
     * @param rewards List of rewards to display
     * @param pendingRewardsService Service for reward persistence
     * @throws IllegalStateException if player already has an active window
     */
    public RewardsContainerWindow(
            String username,
            List<RewardItem> rewards,
            PendingRewardsService pendingRewardsService) {
        super(createOutputOnlyContainer(rewards));

        this.username = username.toLowerCase();
        this.pendingRewardsService = pendingRewardsService;
        this.containerSize = calculateSize(rewards.size());
        this.originalRewards = new ArrayList<>(rewards);

        // Check for existing window (anti-duplication)
        RewardsContainerWindow existing = ACTIVE_WINDOWS.get(this.username);
        if (existing != null) {
            HytaleHubPlugin.getInstance().log(Level.WARNING,
                "Player " + username + " tried to open second rewards window - blocked");
            throw new IllegalStateException("Player already has an active rewards window");
        }

        // Register this window BEFORE clearing rewards
        ACTIVE_WINDOWS.put(this.username, this);

        // Register close event listener (more reliable than onClose0)
        // The closeEventRegistry is inherited from Window class
        // Signature: registerGlobal(short priority, Consumer<EventType>)
        try {
            this.closeEventRegistry.registerGlobal((short) 0, event -> {
                HytaleHubPlugin.getInstance().log(Level.INFO,
                    "WindowCloseEvent received for " + this.username);
                handleWindowClose();
            });
        } catch (Exception e) {
            HytaleHubPlugin.getInstance().log(Level.WARNING,
                "Could not register closeEventRegistry listener: " + e.getMessage());
        }

        // IMPORTANT: Clear rewards NOW - they are in the container
        // On close, we restore what's left
        pendingRewardsService.clearRewards(username);

        HytaleHubPlugin.getInstance().log(Level.INFO,
            "RewardsContainerWindow created for " + username + " with " + rewards.size() +
            " items (OUTPUT_ONLY filter active)");
    }

    /**
     * Creates a container with ALLOW_OUTPUT_ONLY filter.
     * This blocks ALL input actions at the server level:
     * - Clicking to add items
     * - Shift-clicking from inventory
     * - Drag-and-drop onto container
     * - Swapping items
     */
    private static ItemContainer createOutputOnlyContainer(List<RewardItem> rewards) {
        int size = calculateSize(rewards.size());
        SimpleItemContainer container = new SimpleItemContainer((short) size);

        // Add reward items
        for (RewardItem reward : rewards) {
            container.addItemStack(new ItemStack(reward.getItemId(), reward.getQuantity()));
        }

        // THE KEY FIX: Set filter to OUTPUT_ONLY
        // This makes the container read-only (only taking items is allowed)
        container.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);

        return container;
    }

    private static int calculateSize(int itemCount) {
        // Minimum 9 (1 row), rounded up to nearest 9, max 54 (6 rows)
        int size = Math.max(9, ((itemCount + 8) / 9) * 9);
        return Math.min(size, 54);
    }

    /**
     * Called when the window is closed (backup mechanism).
     * Primary close handling is via closeEventRegistry.
     */
    @Override
    public void onClose0(Ref<EntityStore> ref, ComponentAccessor<EntityStore> accessor) {
        HytaleHubPlugin.getInstance().log(Level.INFO,
            "onClose0() called for " + username);
        handleWindowClose();
        super.onClose0(ref, accessor);
    }

    /**
     * Central close handling - called by both onClose0 and closeEventRegistry.
     * Uses processed flag to ensure we only handle once.
     */
    private synchronized void handleWindowClose() {
        if (processed) {
            HytaleHubPlugin.getInstance().log(Level.INFO,
                "Window close already processed for " + username + " - skipping");
            return;
        }
        processed = true;

        HytaleHubPlugin.getInstance().log(Level.INFO,
            "Processing window close for " + username);

        try {
            // Remove from active windows FIRST
            ACTIVE_WINDOWS.remove(username);
            HytaleHubPlugin.getInstance().log(Level.INFO,
                "Removed " + username + " from ACTIVE_WINDOWS (remaining: " + ACTIVE_WINDOWS.size() + ")");

            // Scan for remaining items
            List<RewardItem> remaining = scanRemainingItems();

            if (remaining.isEmpty()) {
                HytaleHubPlugin.getInstance().log(Level.INFO,
                    username + " claimed all " + originalRewards.size() + " rewards");
            } else {
                // Restore unclaimed items
                pendingRewardsService.addRewards(username, remaining);
                int claimed = originalRewards.size() - remaining.size();
                HytaleHubPlugin.getInstance().log(Level.INFO,
                    username + " claimed " + claimed + "/" + originalRewards.size() +
                    " rewards, " + remaining.size() + " restored to pending");
            }
        } catch (Exception e) {
            HytaleHubPlugin.getInstance().log(Level.SEVERE,
                "Error processing window close for " + username + ": " + e.getMessage());
            e.printStackTrace();

            // Safety: Remove from active windows even on error
            ACTIVE_WINDOWS.remove(username);

            // Safety: Try to restore original rewards on error
            try {
                if (!originalRewards.isEmpty()) {
                    pendingRewardsService.addRewards(username, originalRewards);
                    HytaleHubPlugin.getInstance().log(Level.WARNING,
                        "Error recovery: Restored " + originalRewards.size() +
                        " original rewards for " + username);
                }
            } catch (Exception e2) {
                HytaleHubPlugin.getInstance().log(Level.SEVERE,
                    "CRITICAL: Failed to recover rewards for " + username);
            }
        }
    }

    /**
     * Scans the container for items that were NOT taken by the player.
     */
    private List<RewardItem> scanRemainingItems() {
        List<RewardItem> remaining = new ArrayList<>();
        ItemContainer container = getItemContainer();

        for (short i = 0; i < containerSize; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack != null && stack.getQuantity() > 0) {
                remaining.add(new RewardItem(
                    stack.getItemId(),
                    stack.getQuantity(),
                    System.currentTimeMillis(),
                    "unclaimed"
                ));
            }
        }

        return remaining;
    }

    /**
     * Check if a player has an active rewards window.
     */
    public static boolean hasActiveWindow(String username) {
        boolean has = ACTIVE_WINDOWS.containsKey(username.toLowerCase());
        HytaleHubPlugin.getInstance().log(Level.INFO,
            "hasActiveWindow(" + username + ") = " + has + " (total active: " + ACTIVE_WINDOWS.size() + ")");
        return has;
    }

    /**
     * Force-close and cleanup a player's window (for disconnect handling).
     */
    public static void forceCloseWindow(String username) {
        RewardsContainerWindow window = ACTIVE_WINDOWS.remove(username.toLowerCase());
        if (window != null) {
            window.processed = true; // Prevent further processing
            HytaleHubPlugin.getInstance().log(Level.INFO,
                "Force-closed rewards window for " + username);
        }
    }

    /**
     * Get count of active windows (for debugging).
     */
    public static int getActiveWindowCount() {
        return ACTIVE_WINDOWS.size();
    }

    /**
     * Clear all active windows (for shutdown).
     */
    public static void clearAllActiveWindows() {
        int count = ACTIVE_WINDOWS.size();
        ACTIVE_WINDOWS.clear();
        if (count > 0) {
            HytaleHubPlugin.getInstance().log(Level.INFO,
                "Cleared " + count + " active reward windows on shutdown");
        }
    }

    public String getUsername() {
        return username;
    }
}
