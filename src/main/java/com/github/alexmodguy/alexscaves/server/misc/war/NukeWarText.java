package com.github.alexmodguy.alexscaves.server.misc.war;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** Player-facing texts of the community nuke patch. Literal Spanish so the server console and Discord relay match. */
public final class NukeWarText {

    private NukeWarText() {
    }

    public static Component onlyFromWarship() {
        return Component.literal("☢ La bomba nuclear solo se detona soltándola desde un buque de guerra.").withStyle(ChatFormatting.GOLD);
    }

    public static Component aircraftCannotArm() {
        return Component.literal("☢ Esta aeronave no puede armar la bomba nuclear: solo el buque de guerra.").withStyle(ChatFormatting.GOLD);
    }

    public static Component warshipCooldown() {
        return Component.literal("☢ El buque de guerra solo arma una bomba nuclear cada 10 segundos; las demás caen sin armar.").withStyle(ChatFormatting.GOLD);
    }

    public static MutableComponent faction(String name) {
        return Component.literal("«" + name + "»").withStyle(ChatFormatting.YELLOW);
    }

    public static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(command))));
    }
}
