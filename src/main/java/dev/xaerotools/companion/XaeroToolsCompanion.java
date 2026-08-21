package dev.xaerotools.companion;

import com.mojang.logging.LogUtils;
import dev.xaerotools.companion.commands.XtCommand;
import dev.xaerotools.companion.gui.XaeroToolsTab;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.systems.Systems;
import org.slf4j.Logger;

public class XaeroToolsCompanion extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        LOG.info("Initializing XaeroTools Companion");

        // Registered here so Meteor's own Systems.load() (which runs right
        // after addon init) restores it with the rest of the config.
        Systems.add(new XaeroTools());
        Tabs.add(new XaeroToolsTab());
        Commands.add(new XtCommand());
    }

    @Override
    public String getPackage() {
        return "dev.xaerotools.companion";
    }
}
