package com.github.alexmodguy.alexscaves.server.misc.war;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Wars signed by both sides, saved with the overworld (data/alexscaves_nuke_wars.dat). */
public class NukeWarData extends SavedData {

    private static final String FILE_ID = "alexscaves_nuke_wars";

    /** A faction is an Open Parties and Claims party id, or a player UUID for players without a party. */
    public record War(UUID first, UUID second, long sinceMillis) {
        public boolean involves(UUID faction) {
            return first.equals(faction) || second.equals(faction);
        }

        public UUID enemyOf(UUID faction) {
            return first.equals(faction) ? second : first;
        }
    }

    private final Map<String, War> wars = new LinkedHashMap<>();

    public static NukeWarData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(NukeWarData::new, NukeWarData::load, null), FILE_ID);
    }

    static String key(UUID a, UUID b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    public boolean isAtWar(UUID a, UUID b) {
        return wars.containsKey(key(a, b));
    }

    public void startWar(UUID a, UUID b) {
        wars.put(key(a, b), new War(a, b, System.currentTimeMillis()));
        setDirty();
    }

    public boolean endWar(UUID a, UUID b) {
        boolean removed = wars.remove(key(a, b)) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public Collection<War> getWars() {
        return Collections.unmodifiableCollection(wars.values());
    }

    private static NukeWarData load(CompoundTag tag, HolderLookup.Provider provider) {
        NukeWarData data = new NukeWarData();
        ListTag list = tag.getList("Wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag warTag = list.getCompound(i);
            if (warTag.hasUUID("First") && warTag.hasUUID("Second")) {
                War war = new War(warTag.getUUID("First"), warTag.getUUID("Second"), warTag.getLong("Since"));
                data.wars.put(key(war.first(), war.second()), war);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag list = new ListTag();
        for (War war : wars.values()) {
            CompoundTag warTag = new CompoundTag();
            warTag.putUUID("First", war.first());
            warTag.putUUID("Second", war.second());
            warTag.putLong("Since", war.sinceMillis());
            list.add(warTag);
        }
        tag.put("Wars", list);
        return tag;
    }
}
