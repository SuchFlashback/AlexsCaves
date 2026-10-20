package com.github.alexmodguy.alexscaves.server.misc.war;

import com.github.alexmodguy.alexscaves.server.entity.item.NuclearBombEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Called by BombBayMixin when an Immersive Aircraft bomb bay drops a nuclear bomb
 * (config/immersive_aircraft.json: bombBayEntity "alexscaves:nuclear_bomb" -> "alexscaves:nuclear_bomb").
 * Only the warship arms it; from any other aircraft the bomb is defused and drops as an item.
 * A warship bomb bay has one weapon per mount (up to 4 per slot) and all of them fire on one click. The first bomb
 * of a click is armed; the others of the same click are merged into it (they vanish; each extra launcher, i.e. bomb bay
 * slot, makes the explosion bigger, see NuclearBombEntity#addLauncher). Another click within {@link #COOLDOWN_TICKS} only shows the cooldown warning.
 */
public final class NukeAircraft {

    private static final ResourceLocation WARSHIP = ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "warship");
    private static final int COOLDOWN_TICKS = 200;
    private static final int SAME_CLICK_TICKS = 1;
    private static final Map<UUID, Long> LAST_ARMED = new HashMap<>();
    private static final Map<UUID, WeakReference<NuclearBombEntity>> LAST_BOMB = new HashMap<>();

    private NukeAircraft() {
    }

    public static void onBombBayDrop(NuclearBombEntity bomb, Entity aircraft, @Nullable Entity operator, int slot) {
        if (!WARSHIP.equals(BuiltInRegistries.ENTITY_TYPE.getKey(aircraft.getType()))) {
            if (operator instanceof Player player) {
                player.displayClientMessage(NukeWarText.aircraftCannotArm(), true);
            }
            return;
        }
        long now = aircraft.level().getGameTime();
        Long last = LAST_ARMED.get(aircraft.getUUID());
        if (last != null && now >= last && now - last <= SAME_CLICK_TICKS) {
            WeakReference<NuclearBombEntity> ref = LAST_BOMB.get(aircraft.getUUID());
            NuclearBombEntity primary = ref == null ? null : ref.get();
            if (primary != null && !primary.isRemoved() && primary != bomb) {
                bomb.markMerged();
                primary.addLauncher(slot);
                return;
            }
        }
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) {
            if (operator instanceof Player player) {
                player.displayClientMessage(NukeWarText.warshipCooldown(), true);
            }
            return;
        }
        if (LAST_ARMED.size() > 256) {
            LAST_ARMED.values().removeIf(time -> now - time >= COOLDOWN_TICKS || time > now);
            LAST_BOMB.keySet().retainAll(LAST_ARMED.keySet());
        }
        LAST_ARMED.put(aircraft.getUUID(), now);
        LAST_BOMB.put(aircraft.getUUID(), new WeakReference<>(bomb));
        bomb.setFirstLauncher(slot);
        bomb.arm(operator instanceof Player ? operator.getUUID() : null);
    }
}
