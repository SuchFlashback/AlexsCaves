package com.github.alexmodguy.alexscaves.mixincompat;

import com.github.alexmodguy.alexscaves.server.entity.item.NuclearBombEntity;
import com.github.alexmodguy.alexscaves.server.misc.war.NukeAircraft;
import immersive_aircraft.entity.weapon.BombBay;
import immersive_aircraft.entity.weapon.Weapon;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Arms nuclear bombs dropped by Immersive Aircraft 1.7.0 bomb bays (only applied when Immersive Aircraft is installed). */
@Mixin(value = BombBay.class, remap = false)
public abstract class BombBayMixin {

    /** Decides before the ammo is spent whether this mount fires in this click (see NukeAircraft#allowBombBayFire). */
    @Inject(method = "fire(Lorg/joml/Vector3f;)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void ac_planNuclearClick(Vector3f direction, CallbackInfo ci) {
        if (!NukeAircraft.allowBombBayFire((Weapon) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = "getBullet(Lorg/joml/Vector4f;Lorg/joml/Vector3f;)Lnet/minecraft/world/entity/Entity;", at = @At("RETURN"), require = 0)
    private void ac_armNuclearBomb(Vector4f position, Vector3f direction, CallbackInfoReturnable<Entity> cir) {
        if (cir.getReturnValue() instanceof NuclearBombEntity bomb) {
            Weapon weapon = (Weapon) (Object) this;
            NukeAircraft.onBombBayDrop(bomb, weapon.getEntity(), weapon.getOperator(), weapon.getSlot());
        }
    }
}
