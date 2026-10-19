package com.github.alexmodguy.alexscaves.compat.opac;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;
import xaero.pac.common.server.player.config.api.v2.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.v2.PlayerConfigOptions;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * Every call into Open Parties and Claims lives here, so the rest of the mod never loads OPAC classes.
 * Only call it when {@code ModList.get().isLoaded("openpartiesandclaims")} (see NukeWars).
 */
public final class OpacBridge {

    /** Returned by {@link #claimOwner} for server claims and expired claims. */
    public static final UUID SERVER_OWNED = new UUID(0L, 0L);

    private OpacBridge() {
    }

    /** The player who owns the claim on this chunk, {@link #SERVER_OWNED}, or null if the chunk is not claimed. */
    @Nullable
    public static UUID claimOwner(ServerLevel level, int chunkX, int chunkZ) {
        OpenPACServerAPI api = OpenPACServerAPI.get(level.getServer());
        IPlayerChunkClaimAPI claim = api.getServerClaimsManager().get(level.dimension().location(), chunkX, chunkZ);
        if (claim == null) {
            return null;
        }
        UUID owner = claim.getPlayerId();
        if (owner == null || owner.equals(api.getPlayerConfigManager().getServerClaimConfig().getPlayerId())
                || owner.equals(api.getPlayerConfigManager().getExpiredClaimConfig().getPlayerId())) {
            return SERVER_OWNED;
        }
        return owner;
    }

    /** The id of the party the player belongs to, or null. */
    @Nullable
    public static UUID partyOf(MinecraftServer server, UUID player) {
        IServerPartyAPI party = OpenPACServerAPI.get(server).getPartyManager().getPartyByMember(player);
        return party == null ? null : party.getId();
    }

    public static boolean isParty(MinecraftServer server, UUID id) {
        return OpenPACServerAPI.get(server).getPartyManager().getPartyById(id) != null;
    }

    @Nullable
    public static UUID partyOwner(MinecraftServer server, UUID partyId) {
        IServerPartyAPI party = OpenPACServerAPI.get(server).getPartyManager().getPartyById(partyId);
        return party == null ? null : party.getOwner().getUUID();
    }

    @Nullable
    public static String partyName(MinecraftServer server, UUID partyId) {
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        IServerPartyAPI party = api.getPartyManager().getPartyById(partyId);
        if (party == null) {
            return null;
        }
        IPlayerConfigAPI ownerConfig = api.getPlayerConfigManager().getLoadedConfig(party.getOwner().getUUID());
        String name = ownerConfig == null ? "" : ownerConfig.getEffective(PlayerConfigOptions.PARTY_NAME);
        return name == null || name.isBlank() ? party.getDefaultName() : name;
    }

    @Nullable
    public static UUID partyByName(MinecraftServer server, String name) {
        return OpenPACServerAPI.get(server).getPartyManager().getAllStream()
                .filter(party -> name.equalsIgnoreCase(partyName(server, party.getId())))
                .map(IServerPartyAPI::getId).findFirst().orElse(null);
    }

    public static List<String> partyNames(MinecraftServer server) {
        return OpenPACServerAPI.get(server).getPartyManager().getAllStream()
                .map(party -> partyName(server, party.getId())).filter(name -> name != null && !name.isBlank()).toList();
    }
}
