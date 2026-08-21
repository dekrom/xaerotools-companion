package dev.xaerotools.companion.gui;

import dev.xaerotools.companion.XaeroTools;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.Settings;
import net.minecraft.client.gui.screens.Screen;

/**
 * Top-bar tab next to Meteor's own Config: all live-share settings plus a
 * live status line and one-click full sync. Backed by the XaeroTools system,
 * so everything here persists with the rest of Meteor's config.
 */
public class XaeroToolsTab extends Tab {
    public XaeroToolsTab() {
        super("XaeroTools");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new XaeroToolsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof XaeroToolsScreen;
    }

    public static class XaeroToolsScreen extends WindowTabScreen {
        private final Settings settings;
        // Settings.tick clears and rebuilds the container it is given when the
        // settings were invalidated (e.g. right after Systems.load), so the
        // widget list must live alone in its own container — ticking the whole
        // window would wipe the buttons and status label.
        private WVerticalList settingsContainer;
        private WLabel status;

        public XaeroToolsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);

            settings = XaeroTools.get().settings;
            settings.onActivated();

            onClosed(() -> XaeroTools.get().save());
        }

        @Override
        public void initWidgets() {
            settingsContainer = add(theme.verticalList()).expandX().widget();
            settingsContainer.add(theme.settings(settings)).expandX();

            WHorizontalList actions = add(theme.horizontalList()).expandX().widget();
            WButton sync = actions.add(theme.button("Sync all maps")).expandX().widget();
            sync.action = () -> {
                if (requireRunning()) XaeroTools.get().fullSync(null);
            };
            WButton ping = actions.add(theme.button("Send position")).expandX().widget();
            ping.action = () -> {
                if (requireRunning()) XaeroTools.get().sendPosition();
            };

            status = add(theme.label("")).expandX().widget();
        }

        private long hintUntil;

        private boolean requireRunning() {
            if (XaeroTools.get().isRunning()) return true;
            status.set("Status: off — flip 'enabled' first");
            hintUntil = java.lang.System.currentTimeMillis() + 3000;
            return false;
        }

        @Override
        public void tick() {
            super.tick();

            settings.tick(settingsContainer, theme);
            if (java.lang.System.currentTimeMillis() >= hintUntil) {
                status.set("Status: " + XaeroTools.get().statusLine());
            }
        }
    }
}
