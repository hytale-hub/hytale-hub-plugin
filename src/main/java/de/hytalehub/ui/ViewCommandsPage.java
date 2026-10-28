package de.hytalehub.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import de.hytalehub.HytaleHubPlugin;
import de.hytalehub.config.RulesConfig;

import java.util.List;
import java.util.logging.Level;

/**
 * Interactive commands page opened via /commands command.
 * Each command entry is a clickable button. Clicking executes the command
 * in the player's context and closes the page.
 */
public class ViewCommandsPage extends InteractiveCustomUIPage<ViewCommandsPage.Data> {

    private final PlayerRef playerRef;
    private final RulesConfig config;

    public ViewCommandsPage(PlayerRef playerRef, RulesConfig config) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.playerRef = playerRef;
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui,
                     UIEventBuilder events, Store<EntityStore> store) {
        ui.append("HytaleHub_ViewCommandsPage.ui");

        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CloseBtn",
            EventData.of("Action", "Close"),
            false
        );

        buildCommandsList(ui, events);
    }

    private void buildCommandsList(UICommandBuilder ui, UIEventBuilder events) {
        ui.clear("#CommandsList");

        List<RulesConfig.CommandEntry> commands = config.getCommands();
        for (int i = 0; i < commands.size(); i++) {
            RulesConfig.CommandEntry cmd = commands.get(i);
            List<String> lines = wrapText(cmd.getDescription(), 45);
            int rowHeight = 24 + (lines.size() * 16) + 4;

            StringBuilder labelsBuilder = new StringBuilder();
            labelsBuilder.append(
                "    Label { " +
                "      Anchor: (Height: 20); " +
                "      Style: (FontSize: 14, TextColor: #58a6ff, RenderBold: true, VerticalAlignment: Center); " +
                "      Text: \"" + escapeUI(cmd.getCommand()) + "\"; " +
                "    } ");
            for (String line : lines) {
                labelsBuilder.append(
                    "    Label { " +
                    "      Anchor: (Height: 16); " +
                    "      Style: (FontSize: 12, TextColor: #999999, VerticalAlignment: Center); " +
                    "      Text: \"" + escapeUI(line) + "\"; " +
                    "    } ");
            }

            String btnId = "#CmdBtn" + i;

            ui.appendInline("#CommandsList",
                "TextButton " + btnId + " { " +
                "  Anchor: (Height: " + rowHeight + "); " +
                "  LayoutMode: Left; " +
                "  Padding: (Bottom: 12); " +
                "  Background: #1a1a30(0.6); " +
                "  Style: TextButtonStyle(" +
                "    Default: (Background: #1a1a30(0.6))," +
                "    Hovered: (Background: #1a1a30(0.9))," +
                "    Pressed: (Background: #22c55e(0.3))," +
                "  ); " +
                "  Group { " +
                "    Anchor: (Width: 3); " +
                "    Background: #22c55e(0.6); " +
                "  } " +
                "  Group { " +
                "    FlexWeight: 1; " +
                "    LayoutMode: Top; " +
                "    Padding: (Left: 12, Right: 12, Top: 5, Bottom: 5); " +
                labelsBuilder +
                "  } " +
                "}");

            events.addEventBinding(
                CustomUIEventBindingType.Activating,
                btnId,
                EventData.of("Action", "Execute:" + i),
                false
            );
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || data.action.isEmpty()) {
            return;
        }

        if ("Close".equals(data.action)) {
            this.close();
            return;
        }

        if (data.action.startsWith("Execute:")) {
            try {
                int index = Integer.parseInt(data.action.substring("Execute:".length()));
                List<RulesConfig.CommandEntry> commands = config.getCommands();
                if (index >= 0 && index < commands.size()) {
                    String command = commands.get(index).getCommand();
                    // Strip leading slash if present
                    if (command.startsWith("/")) {
                        command = command.substring(1);
                    }
                    this.close();
                    CommandManager.get().handleCommand(playerRef, command);
                    HytaleHubPlugin.getInstance().log(Level.INFO,
                        playerRef.getUsername() + " executed command via /commands: /" + command);
                }
            } catch (NumberFormatException e) {
                // Ignore invalid index
            } catch (Exception e) {
                HytaleHubPlugin.getInstance().log(Level.WARNING,
                    "Error executing command for " + playerRef.getUsername() + ": " + e.getMessage());
            }
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
