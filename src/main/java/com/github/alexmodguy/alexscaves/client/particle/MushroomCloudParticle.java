package com.github.alexmodguy.alexscaves.client.particle;

import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.client.ClientProxy;
import com.github.alexmodguy.alexscaves.client.render.ColorUtil;
import com.github.alexmodguy.alexscaves.client.model.MushroomCloudModel;
import com.github.alexmodguy.alexscaves.client.render.ACRenderTypes;
import com.github.alexmodguy.alexscaves.client.sound.NuclearExplosionSound;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACSoundRegistry;
import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public class MushroomCloudParticle extends Particle {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(AlexsCaves.MODID, "textures/particle/mushroom_cloud.png");
    private static final ResourceLocation TEXTURE_GLOW = ResourceLocation.fromNamespaceAndPath(AlexsCaves.MODID, "textures/particle/mushroom_cloud_glow.png");
    private static final ResourceLocation TEXTURE_PINK = ResourceLocation.fromNamespaceAndPath(AlexsCaves.MODID, "textures/particle/mushroom_cloud_pink.png");
    private static final ResourceLocation TEXTURE_PINK_GLOW = ResourceLocation.fromNamespaceAndPath(AlexsCaves.MODID, "textures/particle/mushroom_cloud_pink_glow.png");
    private static final MushroomCloudModel MODEL = new MushroomCloudModel();
    private static final int BALL_FOR = 10;
    private static final int GLOW_FOR = 20;
    private static final int FADE_SPEED = 10;
    // Community patch: blocks the sound travels per tick (about half the real speed, so the far players do not wait too long)
    private static final float SOUND_BLOCKS_PER_TICK = 34F;
    private final float scale;
    // Community patch: the original scale (explosion size + 0.2), which keeps setting the amount of smoke and the sounds
    private final float effectScale;
    // Community patch: how much bigger the cloud is than the original one; the smoke grows (and spreads) as much
    private final float spread;
    // Community patch: the flash, the dark sky, the screen shake and the muffled sounds only reach this far (horizontally);
    // farther away the cloud is only seen and heard
    private final float nearDistance;
    private final int fadeTicks;

    private boolean playedRinging;
    private boolean playedExplosion;
    private boolean playedRumble;

    private final boolean pink;

    protected MushroomCloudParticle(ClientLevel level, double x, double y, double z, float scale, boolean pink, float cloudScale) {
        super(level, x, y, z);
        this.gravity = 0.0F;
        this.lifetime = (int) Math.ceil(133.33F * scale);
        this.effectScale = scale + 0.2F;
        // Community patch: cloudScale > 0 (sent by the server, nuclear_cloud_scale) sizes the cloud by the crater; 0 = original
        this.scale = cloudScale > 0 ? cloudScale * scale : this.effectScale;
        this.spread = Math.max(1F, this.scale / this.effectScale);
        this.nearDistance = Math.max(128F, 45F * scale);
        this.fadeTicks = Math.max(FADE_SPEED, lifetime / 5);
        this.setSize(3.0F, 3.0F);
        // Community patch: y is where the bomb went off; the fireball is centred on it (half of it above the ground)
        this.setPos(x, y + 0.5F + this.scale * 0.3125F, z);
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.pink = pink;
    }

    public boolean shouldCull() {
        return false;
    }

    public void tick() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double distance = Math.sqrt((camera.x - this.x) * (camera.x - this.x) + (camera.z - this.z) * (camera.z - this.z));
        boolean near = distance <= nearDistance;
        if (near) {
            ((ClientProxy) AlexsCaves.PROXY).renderNukeSkyDarkFor = 70;
            ((ClientProxy) AlexsCaves.PROXY).muteNonNukeSoundsFor = 50;
        }
        boolean large = this.effectScale > 2.0F;
        if(age > BALL_FOR / 2 + 5 + distance / SOUND_BLOCKS_PER_TICK){
            if(!playedExplosion){
                playedExplosion = true;
                playSound(large ? ACSoundRegistry.LARGE_NUCLEAR_EXPLOSION.get() : ACSoundRegistry.NUCLEAR_EXPLOSION.get(), lifetime - 20, lifetime, 0.2F, false);
            }
        }
        if (age < BALL_FOR) {
            if (near) {
                if(!playedRinging && AlexsCaves.CLIENT_CONFIG.nuclearBombFlash.get()){
                    playedRinging = true;
                    playSound(ACSoundRegistry.NUCLEAR_EXPLOSION_RINGING.get(), 100, 50, 0.05F, true);
                }
                ((ClientProxy) AlexsCaves.PROXY).renderNukeFlashFor = 16;
            }
        } else if (age < lifetime - fadeTicks) {
            float life = (float) (Math.log(1 + (age - BALL_FOR) / (float) (lifetime - BALL_FOR))) * 2F;
            float explosionSpread = (12 * life + 4F) * scale;
            for (int i = 0; i < (1 + random.nextInt(2)) * effectScale; i++) {
                Vec3 from = new Vec3(level.random.nextFloat() - 0.5F, level.random.nextFloat() - 0.5F, level.random.nextFloat() - 0.5F).scale(scale * 1.4F).add(this.x, this.y, this.z);
                Vec3 away = new Vec3(level.random.nextFloat() - 0.5F, level.random.nextFloat() - 0.5F, level.random.nextFloat() - 0.5F).scale(2.34F * spread);
                addCloudParticle(ACParticleRegistry.MUSHROOM_CLOUD_SMOKE.get(), from, away);
            }
            for (int j = 0; j < effectScale * effectScale; j++) {
                Vec3 explosionBase = new Vec3((level.random.nextFloat() - 0.5F) * explosionSpread, (-0.6F + level.random.nextFloat() * 0.5F) * explosionSpread * 0.1F, (level.random.nextFloat() - 0.5F) * explosionSpread).add(this.x, this.y, this.z);
                addCloudParticle(ACParticleRegistry.MUSHROOM_CLOUD_EXPLOSION.get(), explosionBase, Vec3.ZERO);
            }
            if(age > BALL_FOR){
                if(!playedRumble){
                    playedRumble = true;
                    playSound(ACSoundRegistry.NUCLEAR_EXPLOSION_RUMBLE.get(), lifetime + 100, lifetime, 0.1F, true);
                }
            }
        }
        super.tick();
    }

    /**
     * Community patch: the smoke is created whatever the distance (level.addParticle drops every particle more than 32 blocks
     * from the camera, so from afar the cloud had no smoke at all), as big as the cloud and as the particle setting allows.
     */
    private void addCloudParticle(ParticleOptions type, Vec3 at, Vec3 speed) {
        ParticleStatus status = Minecraft.getInstance().options.particles().get();
        if (status == ParticleStatus.MINIMAL || status == ParticleStatus.DECREASED && random.nextBoolean()) {
            return;
        }
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(type, at.x, at.y, at.z, speed.x, speed.y, speed.z);
        if (particle != null && spread > 1F) {
            particle.scale(spread);
        }
    }

    private void playSound(SoundEvent soundEvent, int duration, int fadesAt, float fadeInBy, boolean looping){
        Minecraft.getInstance().getSoundManager().queueTickingSound(new NuclearExplosionSound(soundEvent, this.x, this.y, this.z, duration, fadesAt, fadeInBy, looping));
    }

    public void render(VertexConsumer vertexConsumer, Camera camera, float partialTick) {
        Vec3 vec3 = camera.getPosition();
        float f = (float) (Mth.lerp((double) partialTick, this.xo, this.x) - vec3.x());
        float f1 = (float) (Mth.lerp((double) partialTick, this.yo, this.y) - vec3.y());
        float f2 = (float) (Mth.lerp((double) partialTick, this.zo, this.z) - vec3.z());
        PoseStack posestack = new PoseStack();
        posestack.pushPose();
        posestack.translate(f, f1 - 0.5F, f2);
        posestack.scale(-scale, -scale, scale);
        MultiBufferSource.BufferSource multibuffersource$buffersource = Minecraft.getInstance().renderBuffers().bufferSource();
        MODEL.hideFireball(age >= BALL_FOR);
        float life = (float) (Math.log(1 + (age - BALL_FOR + partialTick) / (lifetime - BALL_FOR))) * 2F;
        float glowLife = life < 1F ? 1F - life : 0;
        int left = lifetime - age;
        float alpha = left <= fadeTicks ? left / (float) fadeTicks : 1.0F;
        MODEL.animateParticle(age, ACMath.smin(life, 1.0F, 0.5F), partialTick);
        VertexConsumer baseConsumer = multibuffersource$buffersource.getBuffer(RenderType.entityTranslucent(pink ? TEXTURE_PINK : TEXTURE));
        MODEL.renderToBuffer(posestack, baseConsumer, getLightColor(partialTick), OverlayTexture.NO_OVERLAY, ColorUtil.packColor(1.0F, 1.0F, 1.0F, alpha));
        VertexConsumer glowConsumer1 = multibuffersource$buffersource.getBuffer(ACRenderTypes.getEyesAlphaEnabled(pink ? TEXTURE_PINK : TEXTURE));
        MODEL.renderToBuffer(posestack, glowConsumer1, 240, OverlayTexture.NO_OVERLAY, ColorUtil.packColor(1.0F, 1.0F, 1.0F, alpha));
        VertexConsumer glowConsumer2 = multibuffersource$buffersource.getBuffer(ACRenderTypes.getEyesAlphaEnabled(pink ? TEXTURE_PINK_GLOW : TEXTURE_GLOW));
        MODEL.renderToBuffer(posestack, glowConsumer2, 240, OverlayTexture.NO_OVERLAY, ColorUtil.packColor(1.0F, 1.0F, 1.0F, glowLife * alpha));
        // Community patch: no distance fog on the cloud (only the land fog, not water, lava or blindness), so it stands out
        // above the horizon instead of fading at the render distance
        boolean landFog = RenderSystem.getShaderFogShape() == FogShape.CYLINDER;
        float fogStart = RenderSystem.getShaderFogStart();
        if (landFog) {
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        }
        multibuffersource$buffersource.endBatch();
        if (landFog) {
            RenderSystem.setShaderFogStart(fogStart);
        }
        posestack.popPose();
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.CUSTOM;
    }

    public static class Factory implements ParticleProvider<SimpleParticleType> {

        public Particle createParticle(SimpleParticleType typeIn, ClientLevel worldIn, double x, double y, double z, double xSpeed, double ySpeed, double zSpeed) {
            if (xSpeed == 0.0) {
                xSpeed = 1.0F;
            }
            return new MushroomCloudParticle(worldIn, x, y, z, (float) Math.max(0.5F, xSpeed), ySpeed >= 1.0F, (float) zSpeed);
        }
    }
}
