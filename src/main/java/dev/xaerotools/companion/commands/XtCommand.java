package dev.xaerotools.companion.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xaerotools.companion.XaeroTools;
import meteordevelopment.meteorclient.commands.Command;
//? if >=26.1 {
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
//?} else {
/*import net.minecraft.commands.SharedSuggestionProvider;
*///?}

public class XtCommand extends Command {
    public XtCommand() {
        super("xt", "XaeroTools Companion: toggle, full map sync, status, position ping.");
    }

    @Override
    //? if >=26.1 {
    public void build(LiteralArgumentBuilder<ClientSuggestionProvider> builder) {
    //?} else {
    /*public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
    *///?}
        builder.then(literal("on").executes(ctx -> {
            XaeroTools.get().enabled.set(true);
            info("XaeroTools: live share on.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("off").executes(ctx -> {
            XaeroTools.get().enabled.set(false);
            info("XaeroTools: live share off.");
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("status").executes(ctx -> {
            info("XaeroTools: " + XaeroTools.get().statusLine());
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("sync")
            .executes(ctx -> {
                if (running()) {
                    XaeroTools.get().fullSync(null);
                    info("XaeroTools: scanning every world for upload…");
                }
                return SINGLE_SUCCESS;
            })
            .then(argument("world", StringArgumentType.greedyString()).executes(context -> {
                if (running()) {
                    String world = StringArgumentType.getString(context, "world");
                    XaeroTools.get().fullSync(world);
                    info("XaeroTools: scanning " + world + " for upload…");
                }
                return SINGLE_SUCCESS;
            }))
        );

        builder.then(literal("ping").executes(ctx -> {
            if (running()) {
                XaeroTools.get().sendPosition();
                info("XaeroTools: position sent.");
            }
            return SINGLE_SUCCESS;
        }));
    }

    private boolean running() {
        if (!XaeroTools.get().isRunning()) {
            error("XaeroTools is off — enable it with .xt on or from the XaeroTools tab.");
            return false;
        }
        return true;
    }
}
