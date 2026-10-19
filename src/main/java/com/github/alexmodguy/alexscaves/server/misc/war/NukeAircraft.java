package com.github.alexmodguy.alexscaves.server.misc.war;

import com.github.alexmodguy.alexscaves.server.entity.item.NuclearBombEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Called by BombBayMixin when an Immersive Aircraft bomb bay drops a nuclear bomb
 * (config/immersive_aircraft.json: bombBayEntity "alexscaves:nuclear_bomb" -> "alexscaves:nuclear_bomb").
 * Only the warship arms it; from any other aircraft the bomb is defused and drops as an item.
 * A warship bomb bay has one weapon per mount (up to 4 per slot) and all of them fire on one click, so only
 * one bomb per warship is armed every {@link #COOLDOWN_TICKS}; the rest drop as items too.
 */
public final class NukeAircraft {

    private static final ResourceLocation WARSHIP = ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "warship");
    private static final int COOLDOWN_TICKS = 200;
    private static final Map<UUID, Long> LAST_ARMED = new HashMap<>();

    private NukeAircraft() {
    }

    public static void onBombBayDrop(NuclearBombEntity bomb, Entity aircraft, @Nullable Entity operator) {
        if (!WARSHIP.equals(BuiltInRegistries.ENTITY_TYPE.getKey(aircraft.getType()))) {
            if (operator instanceof Player player) {
                player.displayClientMessage(NukeWarText.aircraftCannotArm(), true);
            }
            return;
        }
        long now = aircraft.level().getGameTime();
        Long last = LAST_ARMED.get(aircraft.getUUID());
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) {
            if (operator instanceof Player player) {
                player.displayClientMessage(NukeWarText.warshipCooldown(), true);
            }
            return;
        }
        if (LAST_ARMED.size() > 256) {
            LAST_ARMED.values().removeIf(time -> now - time >= COOLDOWN_TICKS || time > now);
        }
        LAST_ARMED.put(aircraft.getUUID(), now);
        bomb.arm(operator instanceof Player ? operator.getUUID() : null);
    }
}
