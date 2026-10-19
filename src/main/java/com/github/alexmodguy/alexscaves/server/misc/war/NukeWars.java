package com.github.alexmodguy.alexscaves.server.misc.war;

import com.github.alexmodguy.alexscaves.compat.opac.OpacBridge;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Community patch: nuclear bombs only break blocks inside Open Parties and Claims claims when the claim's faction
 * and the bomber's faction have both signed a war (/guerra). Server claims are always protected, and the
 * acNukesClaimsShield gamerule protects every claim, even at war.
 */
public final class NukeWars {

    public static final GameRules.Key<GameRules.BooleanValue> RULE_CLAIMS_SHIELD =
            GameRules.register("acNukesClaimsShield", GameRules.Category.MISC, GameRules.BooleanValue.create(false));

    public static final long PROPOSAL_MILLIS = 5 * 60 * 1000L;

    public enum ProposalKind { WAR, PEACE }

    public record Faction(UUID id, boolean party) {
    }

    private record Proposal(ProposalKind kind, UUID from, UUID to, long expiresAtMillis) {
    }

    public record PendingProposal(ProposalKind kind, UUID otherFaction, boolean outgoing) {
    }

    private static final Map<String, Proposal> PROPOSALS = new HashMap<>();

    private NukeWars() {
    }

    /** Called from common setup (on the main thread) so the gamerule is registered before any world loads. */
    public static void init() {
    }

    public static boolean claimsModLoaded() {
        return ModList.get().isLoaded("openpartiesandclaims");
    }

    /** Whether a nuke dropped by {@code attacker} (null: no player, e.g. a Nucleeper) may break blocks in this chunk. */
    public static boolean canNukeBreak(ServerLevel level, int chunkX, int chunkZ, @Nullable UUID attacker) {
        if (!claimsModLoaded()) {
            return true;
        }
        UUID owner = OpacBridge.claimOwner(level, chunkX, chunkZ);
        if (owner == null) {
            return true;
        }
        if (owner.equals(OpacBridge.SERVER_OWNED) || attacker == null || level.getGameRules().getBoolean(RULE_CLAIMS_SHIELD)) {
            return false;
        }
        MinecraftServer server = level.getServer();
        UUID attackerFaction = factionOf(server, attacker).id();
        UUID ownerFaction = factionOf(server, owner).id();
        return !attackerFaction.equals(ownerFaction) && NukeWarData.get(server).isAtWar(attackerFaction, ownerFaction);
    }

    public static Faction factionOf(MinecraftServer server, UUID player) {
        UUID party = claimsModLoaded() ? OpacBridge.partyOf(server, player) : null;
        return party == null ? new Faction(player, false) : new Faction(party, true);
    }

    public static boolean isParty(MinecraftServer server, UUID factionId) {
        return claimsModLoaded() && OpacBridge.isParty(server, factionId);
    }

    /** The player who may sign wars and peace for this faction: the party owner, or the player themself. */
    @Nullable
    public static UUID signerOf(MinecraftServer server, UUID factionId) {
        return isParty(server, factionId) ? OpacBridge.partyOwner(server, factionId) : factionId;
    }

    public static String factionName(MinecraftServer server, UUID factionId) {
        if (isParty(server, factionId)) {
            String name = OpacBridge.partyName(server, factionId);
            if (name != null) {
                return name;
            }
        }
        return playerName(server, factionId);
    }

    public static String playerName(MinecraftServer server, UUID player) {
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        if (server.getProfileCache() != null) {
            Optional<GameProfile> profile = server.getProfileCache().get(player);
            if (profile.isPresent()) {
                return profile.get().getName();
            }
        }
        return player.toString().substring(0, 8);
    }

    /** A player name (online or known to the server) or a party name. */
    @Nullable
    public static Faction findFaction(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return factionOf(server, online.getUUID());
        }
        if (claimsModLoaded()) {
            UUID party = OpacBridge.partyByName(server, name);
            if (party != null) {
                return new Faction(party, true);
            }
        }
        if (server.getProfileCache() != null) {
            Optional<GameProfile> profile = server.getProfileCache().get(name);
            if (profile.isPresent()) {
                return factionOf(server, profile.get().getId());
            }
        }
        return null;
    }

    public static List<String> nameSuggestions(MinecraftServer server) {
        List<String> names = new ArrayList<>(List.of(server.getPlayerNames()));
        if (claimsModLoaded()) {
            names.addAll(OpacBridge.partyNames(server));
        }
        return names;
    }

    private static String proposalKey(ProposalKind kind, UUID from, UUID to) {
        return kind + ":" + from + ">" + to;
    }

    private static void removeExpired() {
        long now = System.currentTimeMillis();
        PROPOSALS.values().removeIf(proposal -> proposal.expiresAtMillis() < now);
    }

    public static void propose(ProposalKind kind, UUID from, UUID to) {
        removeExpired();
        PROPOSALS.put(proposalKey(kind, from, to), new Proposal(kind, from, to, System.currentTimeMillis() + PROPOSAL_MILLIS));
    }

    public static boolean hasProposal(ProposalKind kind, UUID from, UUID to) {
        removeExpired();
        return PROPOSALS.containsKey(proposalKey(kind, from, to));
    }

    /** Removes the proposal and returns whether it existed and had not expired. */
    public static boolean takeProposal(ProposalKind kind, UUID from, UUID to) {
        removeExpired();
        return PROPOSALS.remove(proposalKey(kind, from, to)) != null;
    }

    public static void clearProposals(ProposalKind kind, UUID a, UUID b) {
        PROPOSALS.remove(proposalKey(kind, a, b));
        PROPOSALS.remove(proposalKey(kind, b, a));
    }

    /** Pending proposals sent to or by this faction. */
    public static List<PendingProposal> proposalsOf(UUID faction) {
        removeExpired();
        List<PendingProposal> list = new ArrayList<>();
        for (Proposal proposal : PROPOSALS.values()) {
            if (proposal.from().equals(faction)) {
                list.add(new PendingProposal(proposal.kind(), proposal.to(), true));
            } else if (proposal.to().equals(faction)) {
                list.add(new PendingProposal(proposal.kind(), proposal.from(), false));
            }
        }
        return list;
    }
}
