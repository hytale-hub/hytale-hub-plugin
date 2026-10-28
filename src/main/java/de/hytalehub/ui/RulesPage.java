package de.hytalehub.ui;

import com.hypixel.hytale.server.core.Message;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.RulesConfig;
import de.hytalehub.service.RulesAcceptanceService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Interactive UI page showing server rules on first join.
 * Accept button becomes visible after a configurable delay.
 * Decline or ESC dismissal kicks the player.
 */
public class RulesPage extends InteractiveCustomUIPage<RulesPage.Data> {

    private static final Map<String, RulesPage> ACTIVE_PAGES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "HytaleHub-RulesTimer");
                t.setDaemon(true);
                return t;
            });

    private final PlayerRef playerRef;
    private final String username;
    private final RulesConfig config;
    private final RulesAcceptanceService rulesService;
    private final Runnable onAccepted;

    private volatile boolean acceptEnabled = false;
    private volatile boolean handled = false;
    private volatile boolean closed = false;
    private ScheduledFuture<?> timerFuture;

    public RulesPage(
            PlayerRef playerRef,
            RulesConfig config,
            RulesAcceptanceService rulesService,
            Runnable onAccepted) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);

        this.playerRef = playerRef;
        this.username = playerRef.getUsername().toLowerCase();
        this.config = config;
        this.rulesService = rulesService;
        this.onAccepted = onAccepted;

        ACTIVE_PAGES.put(this.username, this);

        // Start countdown timer
        int delay = config.getAcceptDelaySeconds();
        startCountdown(delay);
    }

    private void startCountdown(int totalSeconds) {
        final int[] remaining = {totalSeconds};
        timerFuture = TIMER_EXECUTOR.scheduleAtFixedRate(() -> {
            try {
                // Stop immediately if page was closed or handled
                if (closed || handled) {
                    cancelTimer();
                    return;
                }

                // Check playerRef is still valid before sending updates
                Ref<EntityStore> ref = playerRef.getReference();
                if (ref == null || !ref.isValid()) {
                    cancelTimer();
                    return;
                }

                remaining[0]--;
                if (remaining[0] <= 0) {
                    acceptEnabled = true;
                    UICommandBuilder ui = new UICommandBuilder();
                    UIEventBuilder events = new UIEventBuilder();
                    ui.set("#TimerLabel.Text", "Du kannst die Regeln jetzt akzeptieren.");
                    ui.set("#TimerLabel.Style.TextColor", "#22c55e");
                    ui.set("#AcceptBtn.Background", "#22c55e");
                    ui.set("#AcceptBtn.Style.Default.LabelStyle.TextColor", "#ffffff");
                    events.addEventBinding(
                        CustomUIEventBindingType.Activating,
                        "#AcceptBtn",
                        EventData.of("Action", "Accept"),
                        false
                    );
                    this.sendUpdate(ui, events, false);
                    cancelTimer();
                } else {
                    UICommandBuilder ui = new UICommandBuilder();
                    ui.set("#TimerLabel.Text", "Bitte lies die Regeln (" + remaining[0] + "s...)");
                    this.sendUpdate(ui, false);
                }
            } catch (Exception e) {
                HytaleHubPlugin.getInstance().log(Level.WARNING,
                    "Rules timer error: " + e.getMessage());
                cancelTimer();
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui,
                     UIEventBuilder events, Store<EntityStore> store) {
        ui.append("HytaleHub_RulesPage.ui");

        // Bind decline button
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#DeclineBtn",
            EventData.of("Action", "Decline"),
            false
        );

        // Build rules list from config
        buildRulesList(ui);
    }

    private void buildRulesList(UICommandBuilder ui) {
        ui.clear("#RulesContent");

        List<String> rules = config.getRules();
        for (int i = 0; i < rules.size(); i++) {
            String rule = rules.get(i);

            // Split rule text into lines at word boundaries
            String ruleText = stripLeadingNumber(rule);
            List<String> lines = wrapText(ruleText, 50);
            int rowHeight = 14 + (lines.size() * 18);

            StringBuilder labelsBuilder = new StringBuilder();
            for (String line : lines) {
                labelsBuilder.append(
                    "    Label { " +
                    "      Anchor: (Height: 18); " +
                    "      Style: (FontSize: 13, TextColor: #c8c8d4, VerticalAlignment: Center); " +
                    "      Text: \"" + escapeUI(line) + "\"; " +
                    "    } ");
            }

            ui.appendInline("#RulesContent",
                "Group { " +
                "  Anchor: (Height: " + rowHeight + "); " +
                "  LayoutMode: Left; " +
                "  Padding: (Bottom: 12); " +
                "  Group { " +
                "    Anchor: (Width: 3); " +
                "    Background: #ef4444(0.6); " +
                "  } " +
                "  Group { " +
                "    FlexWeight: 1; " +
                "    LayoutMode: Left; " +
                "    Background: #1a1a30(0.6); " +
                "    Padding: (Left: 12, Right: 12, Top: 5, Bottom: 5); " +
                "    Label { " +
                "      Anchor: (Width: 28, Height: 18); " +
                "      Style: (FontSize: 13, TextColor: #ef4444(0.7), RenderBold: true, VerticalAlignment: Center); " +
                "      Text: \"" + (i + 1) + ".\"; " +
                "    } " +
                "    Group { " +
                "      FlexWeight: 1; " +
                "      LayoutMode: Top; " +
                labelsBuilder +
                "    } " +
                "  } " +
                "}");
        }

        ui.appendInline("#RulesContent",
            "Group { Anchor: (Height: 8); }");
    }

    private String stripLeadingNumber(String rule) {
        return rule.replaceFirst("^\\d+\\.\\s*", "");
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || data.action.isEmpty() || handled) {
            return;
        }

        switch (data.action) {
            case "Accept":
                if (acceptEnabled) {
                    handled = true;
                    cancelTimer();
                    ACTIVE_PAGES.remove(username);
                    rulesService.markAccepted(playerRef.getUsername());
                    HytaleHubPlugin.getInstance().log(Level.INFO,
                        playerRef.getUsername() + " accepted server rules");

                    // Close this page and open commands page
                    Player player = store.getComponent(ref, Player.getComponentType());
                    if (player != null) {
                        player.getPageManager().openCustomPage(ref, store, new CommandsPage(playerRef, config));
                    }

                    if (onAccepted != null) {
                        onAccepted.run();
                    }
                }
                break;

            case "Decline":
                handled = true;
                cancelTimer();
                ACTIVE_PAGES.remove(username);
                kickPlayer();
                break;
        }
    }

    @Override
    public void onDismiss(Ref<EntityStore> ref, Store<EntityStore> store) {
        super.onDismiss(ref, store);
        closed = true;
        cancelTimer();
        ACTIVE_PAGES.remove(username);

        if (!handled) {
            handled = true;
            kickPlayer();
        }
    }

    private void kickPlayer() {
        String reason = config.getDeclineKickMessage();
        try {
            playerRef.getPacketHandler().disconnect(Message.raw(reason));
        } catch (Exception e) {
            HytaleHubPlugin.getInstance().log(Level.WARNING,
                "Failed to kick " + playerRef.getUsername() + ": " + e.getMessage());
        }
    }

    private void cancelTimer() {
        if (timerFuture != null && !timerFuture.isCancelled()) {
            timerFuture.cancel(false);
        }
    }

    private List<String> wrapText(String text, int maxChars) {
        List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        String[] words = text.split(" ");
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            if (current.length() > 0 && current.length() + 1 + word.length() > maxChars) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                if (current.length() > 0) current.append(" ");
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private String escapeUI(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void forceClosePage(String username) {
        RulesPage page = ACTIVE_PAGES.remove(username.toLowerCase());
        if (page != null) {
            page.closed = true;
            page.cancelTimer();
        }
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
