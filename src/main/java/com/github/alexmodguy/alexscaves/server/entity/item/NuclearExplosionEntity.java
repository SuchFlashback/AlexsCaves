package com.github.alexmodguy.alexscaves.server.entity.item;

import com.github.alexmodguy.alexscaves.AlexsCaves;
import com.github.alexmodguy.alexscaves.client.particle.ACParticleRegistry;
import com.github.alexmodguy.alexscaves.server.block.ACBlockRegistry;
import com.github.alexmodguy.alexscaves.server.block.TremorzillaEggBlock;
import com.github.alexmodguy.alexscaves.server.entity.ACEntityRegistry;
import com.github.alexmodguy.alexscaves.server.entity.living.RaycatEntity;
import com.github.alexmodguy.alexscaves.server.entity.living.TremorzillaEntity;
import com.github.alexmodguy.alexscaves.server.misc.ACDamageTypes;
import com.github.alexmodguy.alexscaves.server.misc.ACMath;
import com.github.alexmodguy.alexscaves.server.misc.ACTagRegistry;
import com.github.alexmodguy.alexscaves.server.misc.war.NukeWars;
import com.github.alexmodguy.alexscaves.server.potion.ACEffectRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.world.chunk.TicketController;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

public class NuclearExplosionEntity extends Entity {

    // Community patch: the explosion used to ask NeoForge for forced chunks (ticking) and give them back with the wrong
    // flag, so they were never released and came back at every start: 169 ticking chunks per bomb, for ever. The explosion
    // now uses plain region tickets (not saved); this controller is only kept to purge what older versions left in the world.
    public static final TicketController TICKET_CONTROLLER = new TicketController(ResourceLocation.fromNamespaceAndPath(AlexsCaves.MODID, "nuclear_explosion"), (level, ticketHelper) -> {
        int purged = 0;
        for (UUID owner : new java.util.ArrayList<>(ticketHelper.getEntityTickets().keySet())) {
            ticketHelper.removeAllTickets(owner);
            purged++;
        }
        if (purged > 0) {
            AlexsCaves.LOGGER.info("Released the chunks that {} old nuclear explosions had left forced in {}", purged, level.dimension().location());
        }
    });

    private static final int TREMORZILLA_EGG_HATCH_RADIUS = 36;

    private boolean spawnedParticle = false;
    private boolean hatchedTremorzillaEggs = false;
    private static final EntityDataAccessor<Float> SIZE = SynchedEntityData.defineId(NuclearExplosionEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> NO_GRIEFING = SynchedEntityData.defineId(NuclearExplosionEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> INTENTIONAL_GAME_DESIGN = SynchedEntityData.defineId(NuclearExplosionEntity.class, EntityDataSerializers.BOOLEAN);
    private boolean loadingChunks = false;
    // Community patch: only one explosion carves at a time (the others wait their turn), the carving does not
    // update neighbours (no water/lava flowing in while it works), and the fluids next to the crater get their
    // flow back at the end.
    // Performance (lab5): only the 16x16x16 cubes that touch the crater are carved, in order from the centre; each tick
    // carves columns for a time budget (nuclear_ms_per_tick) instead of a fixed number of cubes; the shape test skips
    // the noise where it cannot matter and the sections that only hold air; the rim of fluids only keeps the fluids
    // that stay outside the crater.
    private static final int MAX_QUEUE_WAIT_TICKS = 1200;
    private static final int MAX_RIM_FLUIDS = 100000;
    private static final int RIM_FLUIDS_PER_TICK = 500;
    // The width noise is in [-1, 1]; (noise - 0.5) * 0.45 + 0.55 <= 0.775, so the crater never reaches past yDist * 1.775 * r * r.
    private static final double MAX_WIDTH_FACTOR = 1.8D;
    // After the minimum 40 ticks the entity effects are only applied every few ticks (the invulnerability window is 10).
    private static final int LATE_EFFECT_INTERVAL = 5;
    // The chunks the explosion works on are kept loaded (and ticking, so the players see the blocks go) with a plain region
    // ticket that is released at the end; they load in the background while the carving works on the ones that are ready.
    private static final TicketType<UUID> NUKE_TICKET = TicketType.create("alexscaves:nuclear_explosion", UUID::compareTo);
    private static final int CHUNK_TICKET_DISTANCE = 2;
    // lab6: a chunk is only loaded right away if nothing has come in for 5 minutes (it was 30 s): a chunk being generated can
    // take long (Streams Reflowing holds the generation up to 45 s while it plans new rivers), and loading it from the main
    // thread froze the server for all that time
    private static final int MAX_CHUNK_WAIT_TICKS = 6000;
    private static final int FINISH_DELAY_TICKS = 5;
    private static final boolean PROFILE = Boolean.getBoolean("alexscaves.nukeprofile");
    private static long serverTickStart = 0L;
    private static long serverTickSumNs = 0L;
    private static long serverTickMaxNs = 0L;
    private static int serverTickCount = 0;
    // time between the end of a tick and the start of the next one: sleep, plus the tasks the server runs between ticks
    // (finishing chunk loads, among others); a stall there makes the server fall behind without a slow tick
    private static long serverTickEnd = 0L;
    private static long serverGapMaxNs = 0L;
    private static final long PROFILE_STALL_NS = 150_000_000L;
    static {
        if (PROFILE) {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> {
                serverTickStart = System.nanoTime();
                if (serverTickEnd != 0L) {
                    long gap = serverTickStart - serverTickEnd;
                    serverGapMaxNs = Math.max(serverGapMaxNs, gap);
                    if (gap > PROFILE_STALL_NS) {
                        AlexsCaves.LOGGER.info("[nukeprofile] stall between ticks: {} ms (explosion {})", ms(gap), NuclearExplosionEntity.activeExplosion == null ? "none" : "t=" + NuclearExplosionEntity.activeExplosion.tickCount);
                    }
                }
            });
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
                serverTickEnd = System.nanoTime();
                long took = serverTickEnd - serverTickStart;
                serverTickSumNs += took;
                serverTickCount++;
                serverTickMaxNs = Math.max(serverTickMaxNs, took);
                if (took > PROFILE_STALL_NS) {
                    AlexsCaves.LOGGER.info("[nukeprofile] slow server tick: {} ms (explosion {})", ms(took), NuclearExplosionEntity.activeExplosion == null ? "none" : "t=" + NuclearExplosionEntity.activeExplosion.tickCount);
                }
                if (NuclearExplosionEntity.activeExplosion == null && serverTickCount >= 200) {
                    AlexsCaves.LOGGER.info("[nukeprofile] server (no explosion) 200 ticks: avg {} ms, max {} ms, max gap between ticks {} ms", ms(serverTickSumNs / serverTickCount), ms(serverTickMaxNs), ms(serverGapMaxNs));
                    serverTickSumNs = serverTickMaxNs = serverGapMaxNs = 0L;
                    serverTickCount = 0;
                }
            });
        }
    }
    private static NuclearExplosionEntity activeExplosion = null;
    private boolean started = false;
    private int waitedTicks = 0;
    private boolean rimScheduled = false;
    private final LongOpenHashSet rimFluids = new LongOpenHashSet();
    // Corners of the cubes to carve (packed BlockPos), nearest to the centre first; null until the first carving tick.
    private long[] cubes = null;
    private int cubeIndex = 0;
    private int carveTicks = 0;
    private int columnIndex = 0;
    private final double[] yDist = new double[16];
    private long statCarveNs, statScanNs, statTickNs, statBlocks, statPositions, statFire, statRimFluids, statRimDropped;
    // profile only: blocks changed per section in this tick, to estimate the block update packets sent to each player
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap tickSectionChanges = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private long statPacketBytes, statWindowPacketBytes, statMaxTickPacketBytes;
    private long statWindowTickNs, statWindowCarveNs, statWindowScanNs, statWindowMaxTickNs;
    private int statTicks, statActiveTicks, statWindowTicks;
    private long statMaxTickNs = 0L;
    private long[] chunkSet = null;
    private boolean ticketsHeld = false;
    private int chunkWaitTicks = 0;
    private final LongOpenHashSet eggChunksDone = new LongOpenHashSet();
    private int finishedTick = -1;
    private boolean hatchScanDone = false;
    private LevelChunk cachedChunk = null;
    private int cachedChunkX = Integer.MIN_VALUE, cachedChunkZ = Integer.MIN_VALUE;
    // lab6: whether each section (packed SectionPos) may hold fluids (palette test), and whether the current cube has any nearby
    private final Long2BooleanOpenHashMap sectionMayHaveFluid = new Long2BooleanOpenHashMap();
    private boolean cubeNearFluid = true;
    private final it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap<BlockState> destroyable = new it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap<>();

    private Explosion dummyExplosion;
    // Community patch: who dropped the bomb (null for Nucleepers, nuclear furnaces, Sacks of Sating...).
    // Blocks in Open Parties and Claims claims are only broken if the claim's faction is at war with the attacker's.
    @Nullable
    private UUID attacker;
    private final Long2BooleanOpenHashMap breakableChunks = new Long2BooleanOpenHashMap();

    public NuclearExplosionEntity(EntityType<?> entityType, Level level) {
        super(entityType, level);
    }

    public void tick() {
        super.tick();
        if (!level().isClientSide && !started && !isNoGriefing()) {
            if (activeExplosion != null && activeExplosion != this && !activeExplosion.isRemoved() && waitedTicks < MAX_QUEUE_WAIT_TICKS) {
                waitedTicks++;
                this.tickCount = 1;
                return;
            }
            started = true;
            activeExplosion = this;
        }
        long tickStart = System.nanoTime();
        long carveNs = 0L;
        long scanNs = 0L;
        int chunksAffected = getChunksAffected();
        int radius = Math.max(1, Math.round(getSize() * 15F));
        if (!spawnedParticle) {
            spawnedParticle = true;
            int particleY = (int) Math.ceil(this.getY());
            while (particleY > level().getMinBuildHeight() && particleY > this.getY() - radius / 2F && isDestroyable(level().getBlockState(BlockPos.containing(this.getX(), particleY, this.getZ())))) {
                particleY--;
            }
            level().addAlwaysVisibleParticle(ACParticleRegistry.MUSHROOM_CLOUD.get(), true, this.getX(), particleY + 2, this.getZ(), this.getSize(), isIntentionalGameDesign() ? 1.0F : 0.0F, 0);
        }
        if (tickCount > 40 && carvingFinished() && (level().isClientSide || isNoGriefing() || hatchedTremorzillaEggs)) {
            if (!level().isClientSide && started && finishedTick < 0) {
                finishedTick = tickCount;
            }
            if (level().isClientSide || !started || tickCount - finishedTick >= FINISH_DELAY_TICKS) {
                if (!level().isClientSide && started && !rimScheduled) {
                    rimScheduled = true;
                    scheduleRimFluids();
                    logSummary(radius);
                }
                this.remove(RemovalReason.DISCARDED);
            }
        } else {
            if (!level().isClientSide && !isNoGriefing()) {
                boolean firstTick = cubes == null;
                if (firstTick) {
                    buildCubes(chunksAffected, radius);
                }
                if (!loadingChunks && !this.isRemoved()) {
                    loadingChunks = true;
                    holdChunks(true);
                }
                if (!hatchedTremorzillaEggs) {
                    hatchedTremorzillaEggs = hatchNearbyTremorzillaEggs();
                }
                if (!firstTick) {
                    long carveStart = System.nanoTime();
                    carve(radius);
                    carveNs = System.nanoTime() - carveStart;
                    if (PROFILE) {
                        estimateBlockPackets();
                    }
                }
            }
            if (level().isClientSide || tickCount <= 40 || tickCount % LATE_EFFECT_INTERVAL == 0) {
                long scanStart = System.nanoTime();
                affectEntities(radius);
                scanNs = System.nanoTime() - scanStart;
            }
        }
        if (started && !level().isClientSide) {
            recordTick(System.nanoTime() - tickStart, carveNs, scanNs);
        }
    }

    private void affectEntities(int radius) {
        AABB killBox = this.getBoundingBox().inflate(radius + radius * 0.5F, radius * 0.6, radius + radius * 0.5F);
        float flingStrength = getSize() * 0.33F;
        float maximumDistance = radius + radius * 0.5F + 1;
        for (LivingEntity entity : this.level().getEntitiesOfClass(LivingEntity.class, killBox)) {
            if (!entity.isAlive()) {
                // corpses still in the list (they stay 20 ticks): nothing more to hurt, irradiate or throw
                continue;
            }
            float dist = entity.distanceTo(this);
            float damage = calculateDamage(dist, maximumDistance);
            Vec3 vec3 = entity.position().subtract(this.position()).add(0, 0.3, 0).normalize();
            float playerFling = entity instanceof Player ? 0.5F * flingStrength : flingStrength;
            if (damage > 0) {
                if (entity instanceof RaycatEntity) {
                    damage = 0;
                } else if (entity.getType().is(ACTagRegistry.RESISTS_RADIATION)) {
                    damage *= 0.25F;
                    playerFling *= 0.1F;
                    if(entity instanceof TremorzillaEntity){
                        playerFling = 0;
                        damage = 0;
                    }
                }
                if(damage > 0){
                    entity.hurt(isIntentionalGameDesign() ? ACDamageTypes.causeIntentionalGameDesign(level().registryAccess()) : ACDamageTypes.causeNukeDamage(level().registryAccess()), damage);
                }
            }
            if (!entity.isAlive()) {
                // killed by this blast: throwing the corpse hundreds of blocks per tick only made the server load chunks far away
                continue;
            }
            entity.setDeltaMovement(vec3.scale(damage * 0.1F * playerFling));
            if(!this.isIntentionalGameDesign()){
                entity.addEffect(new MobEffectInstance(ACEffectRegistry.IRRADIATED, 48000, getSize() <= 1.5F ? 1 : 2, false, false, true));
            }
        }
    }

    private boolean carvingFinished() {
        return cubes == null || cubeIndex >= cubes.length;
    }

    private void recordTick(long tickNs, long carveNs, long scanNs) {
        statTicks++;
        statTickNs += tickNs;
        statCarveNs += carveNs;
        statScanNs += scanNs;
        statMaxTickNs = Math.max(statMaxTickNs, tickNs);
        if (carveNs > 0L) {
            statActiveTicks++;
        }
        if (PROFILE) {
            statWindowTicks++;
            statWindowTickNs += tickNs;
            statWindowCarveNs += carveNs;
            statWindowScanNs += scanNs;
            statWindowMaxTickNs = Math.max(statWindowMaxTickNs, tickNs);
            if (statWindowTicks >= 20) {
                AlexsCaves.LOGGER.info("[nukeprofile] t={} cubes {}/{} blocks={} pos={} block packets {} kB | explosion tick avg {} ms max {} ms (carve {} ms, entities {} ms) | server tick avg {} ms max {} ms, max gap between ticks {} ms",
                        tickCount, cubeIndex, cubes == null ? 0 : cubes.length, statBlocks, statPositions, statWindowPacketBytes / 1024,
                        ms(statWindowTickNs / statWindowTicks), ms(statWindowMaxTickNs), ms(statWindowCarveNs / statWindowTicks), ms(statWindowScanNs / statWindowTicks),
                        ms(serverTickCount == 0 ? 0 : serverTickSumNs / serverTickCount), ms(serverTickMaxNs), ms(serverGapMaxNs));
                statWindowTicks = 0;
                statWindowPacketBytes = 0L;
                statWindowTickNs = statWindowCarveNs = statWindowScanNs = statWindowMaxTickNs = 0L;
                serverTickSumNs = serverTickMaxNs = serverGapMaxNs = 0L;
                serverTickCount = 0;
            }
        }
    }

    /**
     * Profile only: size of this tick's block update packets for one player (1.21.1 ClientboundSectionBlocksUpdatePacket:
     * section position 8 bytes + count + one varlong per block, 2 bytes for air and up to 4 for fire; a single change is a
     * ClientboundBlockUpdatePacket of ~11 bytes), before compression and without the light updates.
     */
    private void estimateBlockPackets() {
        long bytes = 0L;
        for (int count : tickSectionChanges.values()) {
            bytes += count == 1 ? 11 : 11 + 2L * count;
        }
        tickSectionChanges.clear();
        statPacketBytes += bytes;
        statWindowPacketBytes += bytes;
        statMaxTickPacketBytes = Math.max(statMaxTickPacketBytes, bytes);
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1.0E6D);
    }

    private void logSummary(int radius) {
        AlexsCaves.LOGGER.info("Nuclear explosion finished: size {} (radius {}), {} cubes, {} blocks changed, {} ticks ({} carving), carving {} ms in total ({} ms per carving tick, worst explosion tick {} ms), entity effects {} ms, {} chunks held, {} rim fluids ({} over the cap){}",
                getSize(), radius, cubes == null ? 0 : cubes.length, statBlocks, statTicks, statActiveTicks, ms(statCarveNs),
                ms(statActiveTicks == 0 ? 0 : statCarveNs / statActiveTicks), ms(statMaxTickNs), ms(statScanNs), chunkSet == null ? 0 : chunkSet.length, statRimFluids, statRimDropped,
                PROFILE ? String.format(java.util.Locale.ROOT, ", block packets %.0f kB per player (max %.0f kB in a tick)", statPacketBytes / 1024.0, statMaxTickPacketBytes / 1024.0) : "");
    }

    @Override
    public void remove(Entity.RemovalReason removalReason) {
        if (activeExplosion == this) {
            activeExplosion = null;
        }
        if (!level().isClientSide && loadingChunks) {
            loadingChunks = false;
            holdChunks(false);
        }
        super.remove(removalReason);
    }


    private int getChunksAffected() {
        return (int) Math.ceil(this.getSize());
    }

    /** Keeps the chunks of the crater loaded (hold) or lets them go. */
    private void holdChunks(boolean hold) {
        if (!(this.level() instanceof ServerLevel serverLevel) || chunkSet == null) {
            return;
        }
        UUID id = this.getUUID();
        for (long chunk : chunkSet) {
            ChunkPos pos = new ChunkPos(chunk);
            if (hold) {
                serverLevel.getChunkSource().addRegionTicket(NUKE_TICKET, pos, CHUNK_TICKET_DISTANCE, id, true);
            } else {
                serverLevel.getChunkSource().removeRegionTicket(NUKE_TICKET, pos, CHUNK_TICKET_DISTANCE, id, true);
            }
        }
    }

    private float calculateDamage(float dist, float max) {
        float revert = (max - dist) / max;
        float baseDmg = this.getSize() <= 1.5F ? 100 : 100 + (this.getSize() - 1.5F) * 400;
        return revert * baseDmg;
    }

    public void setAttacker(@Nullable UUID attacker) {
        this.attacker = attacker;
    }

    private boolean canBreakAt(int blockX, int blockZ) {
        long key = ChunkPos.asLong(blockX >> 4, blockZ >> 4);
        if (breakableChunks.containsKey(key)) {
            return breakableChunks.get(key);
        }
        long claimStart = PROFILE ? System.nanoTime() : 0L;
        boolean result = !(level() instanceof ServerLevel serverLevel) || NukeWars.canNukeBreak(serverLevel, blockX >> 4, blockZ >> 4, attacker);
        if (PROFILE) {
            long took = System.nanoTime() - claimStart;
            if (took > 20_000_000L) {
                AlexsCaves.LOGGER.info("[nukeprofile] claim lookup for chunk {} {} took {} ms", blockX >> 4, blockZ >> 4, ms(took));
            }
        }
        breakableChunks.put(key, result);
        return result;
    }

    /** Nearest distance along one axis from the centre to the range [low, high]. */
    private static int nearest(int centre, int low, int high) {
        return centre < low ? low - centre : (centre > high ? centre - high : 0);
    }

    /** Row factor of the crater shape (as in the original): smooth minimum over the height above/below the centre. */
    private static double rowFactor(int centreY, int y, int radius) {
        return ACMath.smin(0.6F - Math.abs(centreY - y) / (float) radius, 0.6F, 0.2F);
    }

    /** The 16x16x16 cubes (aligned to the centre, as always) that can touch the crater, nearest first. */
    private void buildCubes(int chunks, int radius) {
        BlockPos center = this.blockPosition();
        int minY = level().getMinBuildHeight();
        int maxY = level().getMaxBuildHeight() - 1;
        double rr = (double) radius * radius;
        java.util.ArrayList<BlockPos> list = new java.util.ArrayList<>();
        for (int i = -chunks; i <= chunks; i++) {
            for (int j = -chunks; j <= chunks; j++) {
                for (int k = -chunks; k <= chunks; k++) {
                    int x0 = center.getX() + i * 16;
                    int y0 = center.getY() + j * 16;
                    int z0 = center.getZ() + k * 16;
                    double best = 0.0D;
                    for (int y = Math.max(y0, minY); y <= Math.min(y0 + 15, maxY); y++) {
                        best = Math.max(best, rowFactor(center.getY(), y, radius));
                    }
                    int dx = nearest(center.getX(), x0, x0 + 15);
                    int dz = nearest(center.getZ(), z0, z0 + 15);
                    if (best > 0.0D && (double) dx * dx + 1.0D + (double) dz * dz <= best * MAX_WIDTH_FACTOR * rr) {
                        list.add(new BlockPos(x0, y0, z0));
                    }
                }
            }
        }
        list.sort(java.util.Comparator.comparingInt(corner -> corner.distManhattan(center)));
        cubes = new long[list.size()];
        for (int n = 0; n < cubes.length; n++) {
            cubes[n] = list.get(n).asLong();
        }
        cubeIndex = 0;
        columnIndex = 0;
        // chunks the crater can reach (1.039 r is how far a column is ever carved; plus a margin for the rim)
        double limit = 1.05D * radius + 2.0D;
        int minChunkX = Mth.floor(center.getX() - limit) >> 4;
        int maxChunkX = Mth.floor(center.getX() + limit) >> 4;
        int minChunkZ = Mth.floor(center.getZ() - limit) >> 4;
        int maxChunkZ = Mth.floor(center.getZ() + limit) >> 4;
        LongArrayList chunkList = new LongArrayList();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                double dx = nearest(center.getX(), chunkX << 4, (chunkX << 4) + 15);
                double dz = nearest(center.getZ(), chunkZ << 4, (chunkZ << 4) + 15);
                if (dx * dx + dz * dz <= limit * limit) {
                    chunkList.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
        chunkSet = chunkList.toLongArray();
    }

    /** The loaded chunk, or null while it is still loading (the tickets bring it in the background; after a long wait it is loaded right away). */
    @Nullable
    private LevelChunk chunkAt(int chunkX, int chunkZ) {
        if (cachedChunk == null || chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
            LevelChunk chunk = ((ServerLevel) level()).getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null && chunkWaitTicks >= MAX_CHUNK_WAIT_TICKS) {
                chunk = level().getChunk(chunkX, chunkZ);
            }
            if (chunk == null) {
                return null;
            }
            cachedChunk = chunk;
            cachedChunkX = chunkX;
            cachedChunkZ = chunkZ;
        }
        return cachedChunk;
    }

    /** Carves columns of the cubes in order until the tick's time budget (nuclear_ms_per_tick) is used up. */
    private void carve(int radius) {
        if (AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get() <= 0) {
            cubeIndex = cubes.length;
            return;
        }
        if (dummyExplosion == null) {
            dummyExplosion = new Explosion(level(), null, this.getX(), this.getY(), this.getZ(), 10.0F, false, Explosion.BlockInteraction.KEEP);
        }
        cachedChunk = null;
        long deadline = System.nanoTime() + AlexsCaves.COMMON_CONFIG.nukeMsPerTick.get() * 1_000_000L;
        // progressive mode: the cubes (nearest first) are released over nuclear_min_carve_seconds
        carveTicks++;
        int minCarveTicks = AlexsCaves.COMMON_CONFIG.nukeMinCarveSeconds.get() * 20;
        int cubeLimit = minCarveTicks <= 0 ? cubes.length : (int) Math.min(cubes.length, Math.ceil(cubes.length * (double) carveTicks / minCarveTicks));
        BlockPos center = this.blockPosition();
        int minY = level().getMinBuildHeight();
        int maxY = level().getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveBelow = new BlockPos.MutableBlockPos();
        while (cubeIndex < cubeLimit) {
            long corner = cubes[cubeIndex];
            int x0 = BlockPos.getX(corner);
            int y0 = BlockPos.getY(corner);
            int z0 = BlockPos.getZ(corner);
            for (int row = 0; row < 16; row++) {
                int y = y0 + row;
                yDist[row] = y < minY || y > maxY ? 0.0D : rowFactor(center.getY(), y, radius);
            }
            cubeNearFluid = mayHaveFluidAround(x0, y0, z0);
            while (columnIndex < 256) {
                int column = columnIndex;
                long columnStart = PROFILE ? System.nanoTime() : 0L;
                if (!carveColumn(x0 + (column >> 4), y0, z0 + (column & 15), radius, center, carve, carveBelow)) {
                    // its chunk is still loading: try again next tick
                    chunkWaitTicks++;
                    return;
                }
                chunkWaitTicks = 0;
                if (PROFILE && System.nanoTime() - columnStart > 50_000_000L) {
                    AlexsCaves.LOGGER.info("[nukeprofile] slow column at {} {} took {} ms", x0 + (column >> 4), z0 + (column & 15), ms(System.nanoTime() - columnStart));
                }
                columnIndex++;
                if (System.nanoTime() >= deadline) {
                    if (columnIndex >= 256) {
                        columnIndex = 0;
                        cubeIndex++;
                    }
                    return;
                }
            }
            columnIndex = 0;
            cubeIndex++;
        }
    }

    /** Carves one column of the cube; false if its chunk is not loaded yet. */
    private boolean carveColumn(int bx, int y0, int bz, int radius, BlockPos center, BlockPos.MutableBlockPos carve, BlockPos.MutableBlockPos carveBelow) {
        if (!canBreakAt(bx, bz)) {
            return true;
        }
        double rr = (double) radius * radius;
        int dx = bx - center.getX();
        int dz = bz - center.getZ();
        // same value as carve.distToLowCornerSqr(centreX, y - 1, centreZ)
        double distToCenter = (double) dx * dx + 1.0D + (double) dz * dz;
        if (distToCenter > 0.6D * MAX_WIDTH_FACTOR * rr) {
            return true;
        }
        LevelChunk chunk = chunkAt(bx >> 4, bz >> 4);
        if (chunk == null) {
            return false;
        }
        int localX = bx & 15;
        int localZ = bz & 15;
        if (!neighbourChunksReady(bx >> 4, bz >> 4, localX, localZ)) {
            return false;
        }
        int sectionIndex = Integer.MIN_VALUE;
        LevelChunkSection section = null;
        boolean onlyAir = false;
        float itemDropModifier = 0.025F / Math.min(1, this.getSize());
        for (int y = 15; y >= 0; y--) {
            double rowFactor = yDist[y];
            if (rowFactor <= 0.0D || distToCenter > rowFactor * MAX_WIDTH_FACTOR * rr) {
                continue;
            }
            int by = y0 + y;
            int index = chunk.getSectionIndex(by);
            if (index != sectionIndex) {
                sectionIndex = index;
                section = chunk.getSection(index);
                onlyAir = section.hasOnlyAir();
            }
            if (onlyAir) {
                continue;
            }
            BlockState state = section.getBlockState(localX, by & 15, localZ);
            // (air never holds a fluid)
            if (state.isAir() || !isDestroyableCached(state)) {
                continue;
            }
            statPositions++;
            float widthSimplexNoise1 = (ACMath.sampleNoise3D(bx, by, bz, radius) - 0.5F) * 0.45F + 0.55F;
            double targetRadius = rowFactor * (radius + widthSimplexNoise1 * radius) * radius;
            if (distToCenter > targetRadius) {
                continue;
            }
            carve.set(bx, by, bz);
            carveBelow.set(bx, by - 1, bz);
            if(state.is(ACBlockRegistry.TREMORZILLA_EGG.get()) && state.getBlock() instanceof TremorzillaEggBlock tremorzillaEggBlock){
                tremorzillaEggBlock.spawnDinosaurs(level(), carve, state);
            }else if (AlexsCaves.COMMON_CONFIG.nukesSpawnItemDrops.get() && random.nextFloat() < itemDropModifier && state.getFluidState().isEmpty()) {
                level().destroyBlock(carve, true);
            } else if (state.getBlock() instanceof TntBlock) {
                state.onBlockExploded(level(), carve, dummyExplosion);
            } else if (PROFILE) {
                long setStart = System.nanoTime();
                level().setBlock(carve, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                long took = System.nanoTime() - setStart;
                if (took > 20_000_000L) {
                    AlexsCaves.LOGGER.info("[nukeprofile] slow block removal of {} at {} took {} ms", state, carve, ms(took));
                }
            } else {
                level().setBlock(carve, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
            statBlocks++;
            if (PROFILE) {
                tickSectionChanges.addTo(net.minecraft.core.SectionPos.asLong(bx >> 4, by >> 4, bz >> 4), 1);
            }
            if (cubeNearFluid) {
                rememberRimFluids(carve, chunk, radius, center);
            }
            if (random.nextFloat() < 0.15) {
                BlockState below = chunk.getBlockState(carveBelow);
                // Fire on a block that the explosion carves a moment later would only be taken away again (and cost thousands of fire ticks and block updates): it is only set where the floor stays.
                if (!below.isAir() && below.getFluidState().isEmpty() && !carvingReaches(carveBelow, below, radius, center)) {
                    // Community patch: no neighbour update, it would wake the water that is still to be carved and leave sheets of water in the crater
                    level().setBlock(carve, Blocks.FIRE.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
                    statFire++;
                }
            }
        }
        return true;
    }

    /**
     * Lithium updates the hoppers next to every block changed without neighbour updates, and looks them up with a chunk
     * request that waits (and runs every pending chunk load on the main thread meanwhile) when the neighbour chunk is still
     * loading: a column on the edge of its chunk is only carved once the chunk next to it is ready.
     */
    private boolean neighbourChunksReady(int chunkX, int chunkZ, int localX, int localZ) {
        if (chunkWaitTicks >= MAX_CHUNK_WAIT_TICKS || (localX != 0 && localX != 15 && localZ != 0 && localZ != 15)) {
            return true;
        }
        net.minecraft.server.level.ServerChunkCache source = ((ServerLevel) level()).getChunkSource();
        return (localX != 0 || source.getChunkNow(chunkX - 1, chunkZ) != null)
                && (localX != 15 || source.getChunkNow(chunkX + 1, chunkZ) != null)
                && (localZ != 0 || source.getChunkNow(chunkX, chunkZ - 1) != null)
                && (localZ != 15 || source.getChunkNow(chunkX, chunkZ + 1) != null);
    }

    /** False only if no section around the cube (one block of margin) can hold a fluid: then the cube has no rim fluids to remember. */
    private boolean mayHaveFluidAround(int x0, int y0, int z0) {
        net.minecraft.server.level.ServerChunkCache source = ((ServerLevel) level()).getChunkSource();
        for (int sectionX = (x0 - 1) >> 4; sectionX <= (x0 + 16) >> 4; sectionX++) {
            for (int sectionZ = (z0 - 1) >> 4; sectionZ <= (z0 + 16) >> 4; sectionZ++) {
                for (int sectionY = (y0 - 1) >> 4; sectionY <= (y0 + 16) >> 4; sectionY++) {
                    long key = net.minecraft.core.SectionPos.asLong(sectionX, sectionY, sectionZ);
                    if (sectionMayHaveFluid.containsKey(key)) {
                        if (sectionMayHaveFluid.get(key)) {
                            return true;
                        }
                        continue;
                    }
                    LevelChunk chunk = source.getChunkNow(sectionX, sectionZ);
                    if (chunk == null) {
                        // not loaded yet: assume it may (not cached)
                        return true;
                    }
                    int index = chunk.getSectionIndexFromSectionY(sectionY);
                    boolean mayHave = index >= 0 && index < chunk.getSectionsCount() && !chunk.getSection(index).hasOnlyAir()
                            && chunk.getSection(index).maybeHas(state -> !state.getFluidState().isEmpty());
                    sectionMayHaveFluid.put(key, mayHave);
                    if (mayHave) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isDestroyableCached(BlockState state) {
        if (destroyable.containsKey(state)) {
            return destroyable.getBoolean(state);
        }
        boolean result = isDestroyable(state);
        destroyable.put(state, result);
        return result;
    }

    /** True if the carving takes (or has taken) the block at this position: the same test as the carving itself. */
    private boolean carvingReaches(BlockPos pos, BlockState state, int radius, BlockPos center) {
        if (pos.getY() < level().getMinBuildHeight() || pos.getY() >= level().getMaxBuildHeight() || !canBreakAt(pos.getX(), pos.getZ()) || !isDestroyableCached(state)) {
            return false;
        }
        double rowFactor = rowFactor(center.getY(), pos.getY(), radius);
        if (rowFactor <= 0.0D) {
            return false;
        }
        double dx = pos.getX() - center.getX();
        double dz = pos.getZ() - center.getZ();
        double distToCenter = dx * dx + 1.0D + dz * dz;
        float widthSimplexNoise1 = (ACMath.sampleNoise3D(pos.getX(), pos.getY(), pos.getZ(), radius) - 0.5F) * 0.45F + 0.55F;
        double targetRadius = rowFactor * (radius + widthSimplexNoise1 * radius) * radius;
        return distToCenter <= targetRadius;
    }

    /** Fluids touching a carved block that the carving does not take itself: they are the rim of the crater. */
    private void rememberRimFluids(BlockPos.MutableBlockPos carve, LevelChunk chunk, int radius, BlockPos center) {
        if (rimFluids.size() >= MAX_RIM_FLUIDS) {
            statRimDropped++;
            return;
        }
        BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            neighbour.setWithOffset(carve, direction);
            LevelChunk neighbourChunk = chunk;
            if ((neighbour.getX() >> 4) != chunk.getPos().x || (neighbour.getZ() >> 4) != chunk.getPos().z) {
                neighbourChunk = ((ServerLevel) level()).getChunkSource().getChunkNow(neighbour.getX() >> 4, neighbour.getZ() >> 4);
                if (neighbourChunk == null) {
                    continue;
                }
            }
            BlockState neighbourState = neighbourChunk.getBlockState(neighbour);
            if (!neighbourState.getFluidState().isEmpty() && !carvingReaches(neighbour, neighbourState, radius, center)) {
                rimFluids.add(neighbour.asLong());
            }
        }
    }

    /** At the end: fluids next to the crater (and not removed by it) start flowing again, as after a vanilla explosion (spread over a few ticks). */
    private void scheduleRimFluids() {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int n = 0;
        statRimFluids = rimFluids.size();
        for (long packed : rimFluids) {
            pos.set(packed);
            FluidState fluid = level().getFluidState(pos);
            if (!fluid.isEmpty()) {
                level().scheduleTick(pos.immutable(), fluid.getType(), fluid.getType().getTickDelay(level()) + n++ / RIM_FLUIDS_PER_TICK);
            }
        }
        rimFluids.clear();
    }

    private boolean isDestroyable(BlockState state) {
        return (!state.is(ACTagRegistry.NUKE_PROOF) && state.getBlock().getExplosionResistance() < AlexsCaves.COMMON_CONFIG.nukeMaxBlockExplosionResistance.get()) || state.is(ACBlockRegistry.TREMORZILLA_EGG.get());
    }

    /**
     * Hatches the Tremorzilla eggs close to the centre, chunk by chunk as they are ready; false while some are still loading
     * (tried again next tick). lab6: a chunk still loading (or being generated) is never waited for while the carving goes
     * on (that froze the server for the whole generation of the chunk); once the carving is over it is loaded right away.
     */
    private boolean hatchNearbyTremorzillaEggs() {
        BlockPos center = this.blockPosition();
        int radius = TREMORZILLA_EGG_HATCH_RADIUS;
        ServerLevel serverLevel = (ServerLevel) level();
        boolean done = true;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int chunkX = (center.getX() - radius) >> 4; chunkX <= (center.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - radius) >> 4; chunkZ <= (center.getZ() + radius) >> 4; chunkZ++) {
                long key = ChunkPos.asLong(chunkX, chunkZ);
                if (eggChunksDone.contains(key)) {
                    continue;
                }
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    if (!carvingFinished()) {
                        done = false;
                        continue;
                    }
                    chunk = serverLevel.getChunk(chunkX, chunkZ);
                }
                hatchEggsIn(chunk, center, radius, mutablePos);
                eggChunksDone.add(key);
            }
        }
        return done;
    }

    private void hatchEggsIn(LevelChunk chunk, BlockPos center, int radius, BlockPos.MutableBlockPos mutablePos) {
        for (int sectionIndex = 0; sectionIndex < chunk.getSectionsCount(); sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
            if (sectionY + 15 < center.getY() - radius || sectionY > center.getY() + radius || section.hasOnlyAir() || !section.maybeHas(state -> state.is(ACBlockRegistry.TREMORZILLA_EGG.get()))) {
                continue;
            }
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!state.is(ACBlockRegistry.TREMORZILLA_EGG.get()) || !(state.getBlock() instanceof TremorzillaEggBlock tremorzillaEggBlock)) {
                            continue;
                        }
                        mutablePos.set((chunk.getPos().x << 4) + x, sectionY + y, (chunk.getPos().z << 4) + z);
                        if (mutablePos.distSqr(center) <= radius * radius && canBreakAt(mutablePos.getX(), mutablePos.getZ())) {
                            tremorzillaEggBlock.spawnDinosaurs(level(), mutablePos.immutable(), state);
                            level().removeBlock(mutablePos, false);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(SIZE, 1.0F);
        builder.define(NO_GRIEFING, false);
        builder.define(INTENTIONAL_GAME_DESIGN, false);
    }

    public float getSize() {
        return this.entityData.get(SIZE);
    }

    public void setSize(float f) {
        this.entityData.set(SIZE, f);
    }

    public boolean isNoGriefing() {
        return this.entityData.get(NO_GRIEFING);
    }

    public void setNoGriefing(boolean noGriefing) {
        this.entityData.set(NO_GRIEFING, noGriefing);
    }

    public boolean isIntentionalGameDesign() {
        return this.entityData.get(INTENTIONAL_GAME_DESIGN);
    }

    public void setIntentionalGameDesign(boolean intentionalGameDesign) {
        this.entityData.set(INTENTIONAL_GAME_DESIGN, intentionalGameDesign);
    }


    @Override
    protected void readAdditionalSaveData(CompoundTag compoundTag) {
        // the chunk tickets are not saved: after a restart the explosion asks for them again
        attacker = compoundTag.hasUUID("Attacker") ? compoundTag.getUUID("Attacker") : null;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compoundTag) {
        compoundTag.putBoolean("WasLoadingChunks", loadingChunks);
        if (attacker != null) {
            compoundTag.putUUID("Attacker", attacker);
        }

    }
}
