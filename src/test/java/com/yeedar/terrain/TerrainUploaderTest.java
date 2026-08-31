package com.yeedar.terrain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainUploaderTest {

    private List<ChunkPlanes> chunks(int count) {
        List<ChunkPlanes> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ChunkPlanes planes = new ChunkPlanes(i % 600, 0);
            for (int c = 0; c < ChunkPlanes.COLUMNS; c++) planes.set(c, 1, 64, 64);
            out.add(planes);
        }
        return out;
    }

    @Test
    @DisplayName("a batch never exceeds what the server accepts")
    void respectsServerBatchLimit() {
        // The server caps at 1024 and answers 422 for the whole request beyond
        // it, so one oversized batch would lose every chunk in it.
        List<List<ChunkPlanes>> batches = TerrainUploader.batches(chunks(2500));

        assertEquals(3, batches.size());
        for (List<ChunkPlanes> batch : batches) {
            assertTrue(batch.size() <= TerrainUploader.MAX_BATCH,
                    "batch of " + batch.size() + " exceeds the server limit");
        }
    }

    @Test
    @DisplayName("every chunk ends up in exactly one batch")
    void losesNothingAndDuplicatesNothing() {
        List<ChunkPlanes> all = chunks(2500);
        int total = 0;
        for (List<ChunkPlanes> batch : TerrainUploader.batches(all)) total += batch.size();
        assertEquals(2500, total);
    }

    @Test
    @DisplayName("an exact multiple does not produce a trailing empty batch")
    void exactMultiple() {
        List<List<ChunkPlanes>> batches =
                TerrainUploader.batches(chunks(TerrainUploader.MAX_BATCH * 2));
        assertEquals(2, batches.size());
    }

    @Test
    @DisplayName("an empty input produces no batches")
    void emptyInput() {
        assertTrue(TerrainUploader.batches(List.of()).isEmpty());
    }

    @Test
    @DisplayName("the batch limit matches the server's")
    void limitMatchesServer() {
        assertEquals(1024, TerrainUploader.MAX_BATCH);
    }

    @Test
    @DisplayName("batching preserves order, so the oldest chunks go first")
    void preservesOrder() {
        List<ChunkPlanes> all = chunks(TerrainUploader.MAX_BATCH + 5);
        List<List<ChunkPlanes>> batches = TerrainUploader.batches(all);

        assertEquals(all.get(0).cx(), batches.get(0).get(0).cx());
        assertEquals(all.get(TerrainUploader.MAX_BATCH).cx(), batches.get(1).get(0).cx());
    }
}
