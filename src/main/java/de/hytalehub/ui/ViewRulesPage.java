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
 * Read-only rules page opened via /rules command.
 * No timer, no accept/decline, no kick - just a close button.
 */
public class ViewRulesPage extends InteractiveCustomUIPage<ViewRulesPage.Data> {

    private final RulesConfig config;

    public ViewRulesPage(PlayerRef playerRef, RulesConfig config) {
        super(playerRef, CustomPageLifetime.CanDismiss, Data.CODEC);
        this.config = config;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder ui,
                     UIEventBuilder events, Store<EntityStore> store) {
        ui.append("HytaleHub_ViewRulesPage.ui");

        events.addEventBinding(
            CustomUIEventBindingType.Activating,
            "#CloseBtn",
            EventData.of("Action", "Close"),
            false
        );

        buildRulesList(ui);
    }

    private void buildRulesList(UICommandBuilder ui) {
        ui.clear("#RulesContent");

        List<String> rules = config.getRules();
        for (int i = 0; i < rules.size(); i++) {
            String rule = rules.get(i);
            String ruleText = rule.replaceFirst("^\\d+\\.\\s*", "");
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
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, Data data) {
        super.handleDataEvent(ref, store, data);

        if ("Close".equals(data.action)) {
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
