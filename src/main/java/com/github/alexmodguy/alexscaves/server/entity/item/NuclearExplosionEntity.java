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
    private static final int MAX_CHUNK_WAIT_TICKS = 600;
    private static final int FINISH_DELAY_TICKS = 5;
    private static final boolean PROFILE = Boolean.getBoolean("alexscaves.nukeprofile");
    private static long serverTickStart = 0L;
    private static long serverTickSumNs = 0L;
    private static long serverTickMaxNs = 0L;
    private static int serverTickCount = 0;
    static {
        if (PROFILE) {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> serverTickStart = System.nanoTime());
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
                long took = System.nanoTime() - serverTickStart;
                serverTickSumNs += took;
                serverTickCount++;
                serverTickMaxNs = Math.max(serverTickMaxNs, took);
                if (NuclearExplosionEntity.activeExplosion == null && serverTickCount >= 200) {
                    AlexsCaves.LOGGER.info("[nukeprofile] server (no explosion) 200 ticks: avg {} ms, max {} ms", ms(serverTickSumNs / serverTickCount), ms(serverTickMaxNs));
                    serverTickSumNs = serverTickMaxNs = 0L;
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
    private int columnIndex = 0;
    private final double[] yDist = new double[16];
    private long statCarveNs, statScanNs, statTickNs, statBlocks, statPositions, statFire;
    private long statWindowTickNs, statWindowCarveNs, statWindowScanNs, statWindowMaxTickNs;
    private int statTicks, statActiveTicks, statWindowTicks;
    private long statMaxTickNs = 0L;
    private long[] chunkSet = null;
    private boolean ticketsHeld = false;
    private int chunkWaitTicks = 0;
    private int eggWaitTicks = 0;
    private int finishedTick = -1;
    private boolean hatchScanDone = false;
    private LevelChunk cachedChunk = null;
    private int cachedChunkX = Integer.MIN_VALUE, cachedChunkZ = Integer.MIN_VALUE;

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
                AlexsCaves.LOGGER.info("[nukeprofile] t={} cubes {}/{} blocks={} pos={} | explosion tick avg {} ms max {} ms (carve {} ms, entities {} ms) | server tick avg {} ms max {} ms",
                        tickCount, cubeIndex, cubes == null ? 0 : cubes.length, statBlocks, statPositions,
                        ms(statWindowTickNs / statWindowTicks), ms(statWindowMaxTickNs), ms(statWindowCarveNs / statWindowTicks), ms(statWindowScanNs / statWindowTicks),
                        ms(serverTickCount == 0 ? 0 : serverTickSumNs / serverTickCount), ms(serverTickMaxNs));
                statWindowTicks = 0;
                statWindowTickNs = statWindowCarveNs = statWindowScanNs = statWindowMaxTickNs = 0L;
                serverTickSumNs = serverTickMaxNs = 0L;
                serverTickCount = 0;
            }
        }
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1.0E6D);
    }

    private void logSummary(int radius) {
        AlexsCaves.LOGGER.info("Nuclear explosion finished: size {} (radius {}), {} cubes, {} blocks changed, {} ticks ({} carving), carving {} ms in total ({} ms per carving tick, worst explosion tick {} ms), entity effects {} ms",
                getSize(), radius, cubes == null ? 0 : cubes.length, statBlocks, statTicks, statActiveTicks, ms(statCarveNs),
                ms(statActiveTicks == 0 ? 0 : statCarveNs / statActiveTicks), ms(statMaxTickNs), ms(statScanNs));
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
        BlockPos center = this.blockPosition();
        int minY = level().getMinBuildHeight();
        int maxY = level().getMaxBuildHeight() - 1;
        BlockPos.MutableBlockPos carve = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos carveBelow = new BlockPos.MutableBlockPos();
        while (cubeIndex < cubes.length) {
            long corner = cubes[cubeIndex];
            int x0 = BlockPos.getX(corner);
            int y0 = BlockPos.getY(corner);
            int z0 = BlockPos.getZ(corner);
            for (int row = 0; row < 16; row++) {
                int y = y0 + row;
                yDist[row] = y < minY || y > maxY ? 0.0D : rowFactor(center.getY(), y, radius);
            }
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
            if ((state.isAir() && state.getFluidState().isEmpty()) || !isDestroyable(state)) {
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
            } else {
                level().setBlock(carve, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
            }
            statBlocks++;
            rememberRimFluids(carve, chunk, radius, center);
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

    /** True if the carving takes (or has taken) the block at this position: the same test as the carving itself. */
    private boolean carvingReaches(BlockPos pos, BlockState state, int radius, BlockPos center) {
        if (pos.getY() < level().getMinBuildHeight() || pos.getY() >= level().getMaxBuildHeight() || !canBreakAt(pos.getX(), pos.getZ()) || !isDestroyable(state)) {
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

    /** Hatches the Tremorzilla eggs close to the centre; false while the chunks around are still loading (tried again next tick). */
    private boolean hatchNearbyTremorzillaEggs() {
        BlockPos center = this.blockPosition();
        int radius = TREMORZILLA_EGG_HATCH_RADIUS;
        ServerLevel serverLevel = (ServerLevel) level();
        List<LevelChunk> chunks = new java.util.ArrayList<>();
        for (int chunkX = (center.getX() - radius) >> 4; chunkX <= (center.getX() + radius) >> 4; chunkX++) {
            for (int chunkZ = (center.getZ() - radius) >> 4; chunkZ <= (center.getZ() + radius) >> 4; chunkZ++) {
                LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    if (++eggWaitTicks < MAX_CHUNK_WAIT_TICKS) {
                        return false;
                    }
                    chunk = serverLevel.getChunk(chunkX, chunkZ);
                }
                chunks.add(chunk);
            }
        }
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (LevelChunk chunk : chunks) {
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
        return true;
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
