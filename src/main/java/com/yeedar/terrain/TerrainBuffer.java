package com.yeedar.terrain;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds sampled chunks until they are uploaded, and remembers what has already
 * been sent so unchanged terrain is never sent twice.
 *
 * That dedup is the whole reason the server's append-only table stays
 * affordable: rows appear when terrain changes, not when a player spends time
 * near it. A player standing still re-samples the same chunks every sweep.
 *
 * Not thread-safe. Sampling and draining both happen on the client tick; the
 * uploader marshals its result back there rather than touching this off-thread.
 */
public final class TerrainBuffer {

    /**
     * How many chunks may wait to be uploaded.
     *
     * Bounded because uploads can fail indefinitely — a wrong token, a server
     * outage — and an unbounded queue inside a running game is a memory leak
     * the player would experience as a stutter, then a crash.
     */
    private static final int DEFAULT_MAX_PENDING = 4096;

    private final int maxPending;
    /** Insertion-ordered so eviction can drop the oldest. */
    private final LinkedHashMap<Long, ChunkPlanes> pending = new LinkedHashMap<>();
    private final Map<Long, Integer> sentHashes = new HashMap<>();

    public TerrainBuffer() { this(DEFAULT_MAX_PENDING); }

    public TerrainBuffer(int maxPending) { this.maxPending = maxPending; }

    /**
     * Pack two signed ints into disjoint halves of one long: cx in the high
     * 32 bits, cz masked into the low 32. The two halves never overlap, so
     * this isn't working around any aliasing between negative coordinates —
     * OR and XOR are equivalent here and either would be correct.
     */
    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** @return true if this chunk is now queued; false if it was unchanged. */
    public boolean offer(ChunkPlanes planes) {
        long k = key(planes.cx(), planes.cz());
        Integer sent = sentHashes.get(k);
        if (sent != null && sent == planes.contentHash()) return false;

        // Replace rather than queue twice: only the newest state matters.
        if (pending.put(k, planes) == null && pending.size() > maxPending) {
            Iterator<Long> oldest = pending.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        return true;
    }

    public int pending() { return pending.size(); }

    /** Take up to `limit` chunks. They stay unconfirmed until sent() or failed(). */
    public List<ChunkPlanes> drain(int limit) {
        List<ChunkPlanes> batch = new ArrayList<>(Math.min(limit, pending.size()));
        Iterator<Map.Entry<Long, ChunkPlanes>> it = pending.entrySet().iterator();
        while (it.hasNext() && batch.size() < limit) {
            batch.add(it.next().getValue());
            it.remove();
        }
        return batch;
    }

    /** Confirmed stored: remember the hash so it is not sent again. */
    public void sent(List<ChunkPlanes> batch) {
        for (ChunkPlanes planes : batch) {
            sentHashes.put(key(planes.cx(), planes.cz()), planes.contentHash());
        }
    }

    /** Upload failed: put them back, so a failure is a retry rather than a loss. */
    public void failed(List<ChunkPlanes> batch) {
        for (ChunkPlanes planes : batch) offer(planes);
    }

    /** Dimension change, disconnect: every hash we hold is about another world. */
    public void reset() {
        pending.clear();
        sentHashes.clear();
    }
}
