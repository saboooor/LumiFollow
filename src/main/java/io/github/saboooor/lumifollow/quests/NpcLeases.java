package io.github.saboooor.lumifollow.quests;

import net.citizensnpcs.api.npc.NPC;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Existing Citizens NPCs are shared world entities: never retarget another player's escort. */
final class NpcLeases {
    private final Map<NPC, Object> owners = new IdentityHashMap<>();
    boolean acquire(NPC npc, Object owner) {
        Object current = owners.get(npc);
        if (current != null && current != owner) return false;
        owners.put(npc, owner);
        return true;
    }
    boolean release(NPC npc, Object owner) {
        if (owners.get(npc) != owner) return false;
        owners.remove(npc);
        return true;
    }
    boolean forceRelease(NPC npc) {
        return owners.remove(npc) != null;
    }
    Object owner(NPC npc) {
        return owners.get(npc);
    }
    Map<NPC, Object> snapshot() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(owners));
    }
    void clear() { owners.clear(); }
}
