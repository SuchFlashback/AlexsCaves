package com.github.alexmodguy.alexscaves.server.misc.war;

import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.server.entity.item.NuclearBombEntity;
import immersive_aircraft.config.Config;
import immersive_aircraft.entity.InventoryVehicleEntity;
import immersive_aircraft.entity.weapon.BombBay;
import immersive_aircraft.entity.weapon.BulletWeapon;
import immersive_aircraft.entity.weapon.Weapon;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Called by BombBayMixin when an Immersive Aircraft bomb bay drops a nuclear bomb
 * (config/immersive_aircraft.json: bombBayEntity "alexscaves:nuclear_bomb" -> "alexscaves:nuclear_bomb").
 * Only the warship arms it; from any other aircraft the bomb is defused and drops as an item.
 * A warship bomb bay has one weapon per mount (up to 4 per slot) and all of them fire on one click. The first bomb
 * of a click is armed; the others of the same click are merged into it (they vanish; each extra launcher, i.e. bomb bay
 * slot, makes the explosion bigger, see NuclearBombEntity#addLauncher). Another click within {@link #COOLDOWN_TICKS} only shows the cooldown warning.
 * Before any mount spends ammo, {@link #allowBombBayFire} decides which mounts fire in a click: all the mounts of the
 * first nuclear_launcher_max launchers when there are at least two launchers and a nuclear bomb for each of those
 * mounts (big explosion), otherwise a single mount (one bomb, normal explosion); none during the cooldown.
 */
public final class NukeAircraft {

    private static final ResourceLocation WARSHIP = ResourceLocation.fromNamespaceAndPath("immersive_aircraft", "warship");
    private static final int COOLDOWN_TICKS = 200;
    private static final int SAME_CLICK_TICKS = 1;
    private static final Map<UUID, Long> LAST_ARMED = new HashMap<>();
    private static final Map<UUID, WeakReference<NuclearBombEntity>> LAST_BOMB = new HashMap<>();
    private static final Map<UUID, ClickPlan> PLANS = new HashMap<>();
    private static final String NUCLEAR_BOMB = "alexscaves:nuclear_bomb";
    private static final int AMMO_PER_DROP = 20; // BombBay#fire spends 20 per drop

    /** The mounts that may still fire in the click that started at {@code tick}. */
    private record ClickPlan(long tick, Set<Weapon> allowed) {
    }

    private NukeAircraft() {
    }

    /**
     * Called by BombBayMixin before a bomb bay fires (and spends ammo). Returns false when this mount must not fire.
     * Only nuclear bombs from a warship are planned; anything else fires as Immersive Aircraft does.
     */
    public static boolean allowBombBayFire(Weapon weapon) {
        Entity aircraft = weapon.getEntity();
        if (aircraft.level().isClientSide || !(aircraft instanceof InventoryVehicleEntity vehicle)
                || !WARSHIP.equals(BuiltInRegistries.ENTITY_TYPE.getKey(aircraft.getType()))
                || !(weapon instanceof BulletWeapon bullet) || !usesNuclearBomb(vehicle, bullet)) {
            return true;
        }
        long now = aircraft.level().getGameTime();
        ClickPlan plan = PLANS.get(aircraft.getUUID());
        if (plan == null || now < plan.tick() || now - plan.tick() > SAME_CLICK_TICKS) {
            plan = new ClickPlan(now, planClick(vehicle, weapon, now));
            if (PLANS.size() > 256) {
                PLANS.values().removeIf(p -> now - p.tick() > SAME_CLICK_TICKS || p.tick() > now);
            }
            PLANS.put(aircraft.getUUID(), plan);
        }
        return plan.allowed().remove(weapon);
    }

    private static Set<Weapon> planClick(InventoryVehicleEntity vehicle, Weapon first, long now) {
        Set<Weapon> allowed = Collections.newSetFromMap(new IdentityHashMap<>());
        Long last = LAST_ARMED.get(vehicle.getUUID());
        if (last != null && now >= last && now - last < COOLDOWN_TICKS) {
            if (first.getOperator() instanceof Player player) {
                player.displayClientMessage(NukeWarText.warshipCooldown(), true);
            }
            return allowed;
        }
        List<Integer> slots = new ArrayList<>();
        for (Map.Entry<Integer, List<Weapon>> e : vehicle.getWeapons().entrySet()) {
            if (e.getValue().stream().anyMatch(w -> w instanceof BombBay)) {
                slots.add(e.getKey());
            }
        }
        Collections.sort(slots);
        int max = AlexsCaves.COMMON_CONFIG.nukeLauncherMax.get();
        List<Weapon> big = new ArrayList<>();
        for (int i = 0; i < slots.size() && i < max; i++) {
            for (Weapon w : vehicle.getWeapons().get(slots.get(i))) {
                if (w instanceof BombBay) {
                    big.add(w);
                }
            }
        }
        boolean creative = first.getOperator() instanceof Player player && player.isCreative();
        if (Math.min(slots.size(), max) >= 2 && (creative || availableBombs(vehicle, big) >= big.size())) {
            allowed.addAll(big);
        } else {
            allowed.add(first);
        }
        return allowed;
    }

    /** Nuclear bombs this click can use: those already loaded in the mounts plus those in the inventory. */
    private static int availableBombs(InventoryVehicleEntity vehicle, List<Weapon> mounts) {
        int bombs = 0;
        for (Weapon w : mounts) {
            if (w instanceof BulletWeapon b && b.getAmmo() >= AMMO_PER_DROP && isNuclear(b.getAmmoStack())) {
                bombs++;
            }
        }
        for (int i = 0; i < vehicle.getInventory().getContainerSize(); i++) {
            ItemStack stack = vehicle.getInventory().getItem(i);
            if (isNuclear(stack)) {
                bombs += stack.getCount();
            }
        }
        return bombs;
    }

    /** Whether this mount's next drop is a nuclear bomb (BulletWeapon#spentAmmo takes the first ammo item in the inventory). */
    private static boolean usesNuclearBomb(InventoryVehicleEntity vehicle, BulletWeapon weapon) {
        if (weapon.getAmmo() >= AMMO_PER_DROP) {
            return isNuclear(weapon.getAmmoStack());
        }
        Map<String, Integer> ammunition = Config.getInstance().bombBayAmmunition;
        for (int i = 0; i < vehicle.getInventory().getContainerSize(); i++) {
            ItemStack stack = vehicle.getInventory().getItem(i);
            if (!stack.isEmpty() && ammunition.containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
                return isNuclear(stack);
            }
        }
        return false;
    }

    private static boolean isNuclear(@Nullable ItemStack stack) {
        return stack != null && !stack.isEmpty() && NUCLEAR_BOMB.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
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
