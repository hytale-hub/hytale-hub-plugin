package de.hytalehub.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.model.RewardItem;
import de.hytalehub.model.RewardItems;
import de.hytalehub.service.PendingRewardsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Interactive UI Page for claiming rewards with pagination support.
 * Uses the same pattern as AdminUI (Buuz135) - proven to work.
 */
public class RewardsPage extends InteractiveCustomUIPage<RewardsPage.Data> {

    private static final Map<String, RewardsPage> ACTIVE_PAGES = new ConcurrentHashMap<>();
    private static final int ITEMS_PER_PAGE = 6;

    private final String username;
    private final List<RewardItem> rewards;
    private final PendingRewardsService rewardsService;
    private int currentPage = 0;

    public RewardsPage(
            PlayerRef playerRef,
            String username,
            List<RewardItem> rewards,
            PendingRewardsService rewardsService) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);

        this.username = username.toLowerCase();
        this.rewards = new ArrayList<>(rewards);
        this.rewardsService = rewardsService;

        ACTIVE_PAGES.put(this.username, this);

        HytaleHubPlugin.getInstance().log(Level.INFO,
            "RewardsPage created for " + username + " with " + rewards.size() + " rewards");
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui,
                     UIEventBuilder events, Store<EntityStore> store) {

        // Load the page UI from .ui file (same pattern as AdminUI)
        ui.append("HytaleHub_RewardsPage.ui");

        // Register button event bindings
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#PrevPage",
            EventData.of("Action", "PrevPage"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#NextPage",
            EventData.of("Action", "NextPage"),
            false
        );
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#ClaimAll",
            EventData.of("Action", "ClaimAll"),
            false
        );

        buildRewardsList(ui, events);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || data.action.isEmpty()) {
            return;
        }

        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return;
        }

        HytaleHubPlugin.getInstance().log(Level.INFO,
            "RewardsPage action: " + data.action + " from " + username);

        // Votes can add rewards while the page is open. Always work on the stored list;
        // new rewards are appended, so the indexes shown on the page stay valid.
        refreshRewards();

        boolean needsUpdate = false;

        switch (data.action) {
            case "PrevPage":
                if (currentPage > 0) {
                    currentPage--;
                    needsUpdate = true;
                }
                break;

            case "NextPage":
                if (hasNextPage()) {
                    currentPage++;
                    needsUpdate = true;
                }
                break;

            case "ClaimAll":
                needsUpdate = claimAllRewards(player);
                break;

            default:
                if (data.action.startsWith("Claim:")) {
                    try {
                        int index = Integer.parseInt(data.action.substring(6));
                        needsUpdate = claimReward(player, index);
                    } catch (NumberFormatException e) {
                        // Invalid index
                    }
                }
                break;
        }

        if (needsUpdate) {
            UICommandBuilder ui = new UICommandBuilder();
            UIEventBuilder events = new UIEventBuilder();
            buildRewardsList(ui, events);
            this.sendUpdate(ui, events, false);
        }
    }

    private void buildRewardsList(UICommandBuilder ui, UIEventBuilder events) {
        ui.clear("#RewardCards");

        String totalText = rewards.size() + " Reward" + (rewards.size() != 1 ? "s" : "") + " verfuegbar";
        ui.set("#TotalInfo.Text", totalText);

        if (rewards.isEmpty()) {
            ui.appendInline("#RewardCards",
                "Label { " +
                "  Style: (FontSize: 14, TextColor: #888888); " +
                "  Text: \"Keine Rewards vorhanden.\"; " +
                "  Anchor: (Height: 40); " +
                "}");

            ui.set("#PageInfo.Text", "0 / 0");
            return;
        }

        int startIndex = currentPage * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, rewards.size());

        if (startIndex >= rewards.size() && currentPage > 0) {
            currentPage = Math.max(0, (rewards.size() - 1) / ITEMS_PER_PAGE);
            startIndex = currentPage * ITEMS_PER_PAGE;
            endIndex = Math.min(startIndex + ITEMS_PER_PAGE, rewards.size());
        }

        for (int i = startIndex; i < endIndex; i++) {
            RewardItem reward = rewards.get(i);
            int localIndex = i - startIndex;
            String itemName = formatItemName(reward.getItemId());
            String quantity = "x" + reward.getQuantity();

            // Create a row for each reward item with accent border
            ui.appendInline("#RewardCards",
                "Group { " +
                "  Anchor: (Height: 52); " +
                "  LayoutMode: Left; " +
                "  Padding: (Bottom: 4); " +
                "  Group { " +
                "    Anchor: (Width: 3); " +
                "    Background: #a855f7(0.6); " +
                "  } " +
                "  Group { " +
                "    FlexWeight: 1; " +
                "    LayoutMode: Left; " +
                "    Background: #1a1a30(0.6); " +
                "    Padding: (Left: 12, Right: 10, Top: 5, Bottom: 5); " +
                "    Label #Name" + localIndex + " { " +
                "      FlexWeight: 1; " +
                "      Style: (FontSize: 13, TextColor: #c8c8d4, VerticalAlignment: Center); " +
                "      Text: \"" + escapeUI(itemName) + "\"; " +
                "    } " +
                "    Label #Qty" + localIndex + " { " +
                "      Anchor: (Width: 50); " +
                "      Style: (FontSize: 13, TextColor: #22c55e, RenderBold: true, VerticalAlignment: Center); " +
                "      Text: \"" + escapeUI(quantity) + "\"; " +
                "    } " +
                "    TextButton #ClaimBtn" + localIndex + " { " +
                "      Anchor: (Width: 80, Height: 30); " +
                "      Background: #22c55e(0.15); " +
                "      Text: \"Abholen\"; " +
                "      Style: TextButtonStyle(" +
                "        Default: (LabelStyle: (FontSize: 12, TextColor: #22c55e, HorizontalAlignment: Center, VerticalAlignment: Center)), " +
                "        Hovered: (Background: #22c55e(0.3), LabelStyle: (FontSize: 12, TextColor: #4ade80, HorizontalAlignment: Center, VerticalAlignment: Center)), " +
                "        Pressed: (Background: #22c55e(0.5), LabelStyle: (FontSize: 12, TextColor: #ffffff, HorizontalAlignment: Center, VerticalAlignment: Center))" +
                "      ); " +
                "    } " +
                "  } " +
                "}");

            // Bind claim button using array index (confirmed working from AdminUI)
            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#RewardCards[" + localIndex + "] #ClaimBtn" + localIndex,
                EventData.of("Action", "Claim:" + i),
                false
            );
        }

        int totalPages = Math.max(1, (int) Math.ceil(rewards.size() / (double) ITEMS_PER_PAGE));
        ui.set("#PageInfo.Text", (currentPage + 1) + " / " + totalPages);
    }

    private String escapeUI(String input) {
        if (input == null) return "";
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private boolean claimReward(Player player, int index) {
        if (index < 0 || index >= rewards.size()) {
            return false;
        }

        RewardItem reward = rewards.get(index);
        if (!RewardItems.isUsable(reward)) {
            HytaleHubPlugin.getInstance().log(Level.WARNING,
                "Unknown reward item for " + username + ": " + reward + " (check config.json)");
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Dieses Item gibt es auf dem Server nicht. Bitte melde das einem Admin.").color("#ef4444")
            ));
            return false;
        }
        ItemStack stack = new ItemStack(reward.getItemId(), reward.getQuantity());

        // Check BEFORE adding - prevent item loss
        if (!player.getInventory().getCombinedStorageFirst().canAddItemStack(stack)) {
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Nicht genug Platz im Inventar! Bitte raeume Platz frei.").color("#ef4444")
            ));
            return false;
        }

        if (player.getInventory().getCombinedStorageFirst().addItemStack(stack).succeeded()) {
            rewardsService.removeReward(username, reward);
            rewards.remove(index);

            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Reward erhalten: ").color("#22c55e"),
                Message.raw(formatItemName(reward.getItemId()) + " x" + reward.getQuantity()).color("#ffffff")
            ));

            HytaleHubPlugin.getInstance().log(Level.INFO,
                username + " claimed: " + reward.getItemId() + " x" + reward.getQuantity());
            return true;
        } else {
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw("Fehler beim Abholen. Versuche es erneut.").color("#ef4444")
            ));
            return false;
        }
    }

    private boolean claimAllRewards(Player player) {
        if (rewards.isEmpty()) {
            return false;
        }

        int claimed = 0;
        int invalid = 0;
        int noSpace = 0;

        // Each item is removed from storage right after it landed in the inventory. If anything
        // throws halfway, the items already handed out are gone from the list and cannot be claimed twice.
        for (RewardItem reward : new ArrayList<>(rewards)) {
            if (!RewardItems.isUsable(reward)) {
                invalid++;
                HytaleHubPlugin.getInstance().log(Level.WARNING,
                    "Unknown reward item for " + username + ": " + reward + " (check config.json)");
                continue;
            }
            try {
                ItemStack stack = new ItemStack(reward.getItemId(), reward.getQuantity());
                // Check space BEFORE adding each item
                if (player.getInventory().getCombinedStorageFirst().canAddItemStack(stack)
                        && player.getInventory().getCombinedStorageFirst().addItemStack(stack).succeeded()) {
                    rewardsService.removeReward(username, reward);
                    claimed++;
                } else {
                    noSpace++;
                }
            } catch (Throwable e) {
                invalid++;
                HytaleHubPlugin.getInstance().log(Level.WARNING,
                    "Could not hand out " + reward + " to " + username + ": " + e);
            }
        }

        refreshRewards();

        if (claimed > 0) {
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw(claimed + " Reward" + (claimed != 1 ? "s" : "") + " erhalten!").color("#22c55e")
            ));
        }

        if (noSpace > 0) {
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw(noSpace + " nicht abgeholt (Inventar voll).").color("#fbbf24")
            ));
        }

        if (invalid > 0) {
            this.playerRef.sendMessage(Message.join(
                Message.raw("[Hytale-Hub] ").color("#a855f7").bold(true),
                Message.raw(invalid + " Item(s) gibt es auf dem Server nicht. Bitte melde das einem Admin.").color("#ef4444")
            ));
        }

        if (!rewards.isEmpty()) {
            int maxPage = (rewards.size() - 1) / ITEMS_PER_PAGE;
            if (currentPage > maxPage) {
                currentPage = maxPage;
            }
        }

        return true;
    }

    private void refreshRewards() {
        rewards.clear();
        rewards.addAll(rewardsService.getRewards(username));
    }

    private String formatItemName(String itemId) {
        if (itemId == null) return "Unknown";
        return itemId.replace("_", " ");
    }

    private boolean hasNextPage() {
        return (currentPage + 1) * ITEMS_PER_PAGE < rewards.size();
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        super.onDismiss(ref, store);
        ACTIVE_PAGES.remove(username);
        HytaleHubPlugin.getInstance().log(Level.INFO,
            "RewardsPage closed for " + username);
    }

    public static boolean hasActivePage(String username) {
        return ACTIVE_PAGES.containsKey(username.toLowerCase());
    }

    public static void forceClosePage(String username) {
        ACTIVE_PAGES.remove(username.toLowerCase());
    }

    public static class Data {
        public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
            .addField(
                new KeyedCodec<>("Action", Codec.STRING),
                (d, s) -> d.action = s,
                d -> d.action
            )
            .build();

        private String action;
    }
}
