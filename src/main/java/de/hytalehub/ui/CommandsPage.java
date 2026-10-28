package de.hytalehub.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import de.hytalehub.config.RulesConfig;

import java.util.List;

/**
 * Interactive UI page showing important server commands.
 * Displayed after the player accepts the rules.
 */
public class CommandsPage extends InteractiveCustomUIPage<CommandsPage.Data> {

    private final RulesConfig config;

    public CommandsPage(PlayerRef playerRef, RulesConfig config) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui,
                     UIEventBuilder events, Store<EntityStore> store) {
        ui.append("HytaleHub_CommandsPage.ui");

        // Bind understood button
        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#UnderstoodBtn",
            EventData.of("Action", "Understood"),
            false
        );

        // Build commands list
        buildCommandsList(ui);
    }

    private void buildCommandsList(UICommandBuilder ui) {
        ui.clear("#CommandsList");

        List<RulesConfig.CommandEntry> commands = config.getCommands();
        for (RulesConfig.CommandEntry cmd : commands) {
            // Split description into lines of ~45 chars at word boundaries
            List<String> lines = wrapText(cmd.getDescription(), 45);
            int rowHeight = 24 + (lines.size() * 16);

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

            ui.appendInline("#CommandsList",
                "Group { " +
                "  Anchor: (Height: " + rowHeight + "); " +
                "  LayoutMode: Left; " +
                "  Padding: (Bottom: 12); " +
                "  Group { " +
                "    Anchor: (Width: 3); " +
                "    Background: #22c55e(0.6); " +
                "  } " +
                "  Group { " +
                "    FlexWeight: 1; " +
                "    LayoutMode: Top; " +
                "    Background: #1a1a30(0.6); " +
                "    Padding: (Left: 12, Right: 12, Top: 5, Bottom: 5); " +
                labelsBuilder +
                "  } " +
                "}");
        }
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        super.handleDataEvent(ref, store, data);

        if ("Understood".equals(data.action)) {
            this.close();
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
