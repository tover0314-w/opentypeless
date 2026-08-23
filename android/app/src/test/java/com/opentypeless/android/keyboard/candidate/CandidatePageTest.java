package com.opentypeless.android.keyboard.candidate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class CandidatePageTest {
    @Test
    public void latinAndRimeUseTheSameStableSelectionContract() {
        CandidatePage latin = page("latin", 7L, 11L, 0, 1, "hello", "help");
        CandidatePage rime = page("rime", 9L, 23L, 1, 3, "你", "泥");

        CandidatePage.Selection latinSelection = latin.selection(1);
        CandidatePage.Selection rimeSelection = rime.selection(0);

        assertEquals("latin", latinSelection.producerId());
        assertEquals(11L, latinSelection.pageRevision());
        assertEquals("c1", latinSelection.candidateId());
        assertEquals("help", latinSelection.expectedText());
        assertEquals("rime", rimeSelection.producerId());
        assertEquals(23L, rimeSelection.pageRevision());
        assertEquals(1, rimeSelection.pageIndex());
        assertEquals("你", rimeSelection.expectedText());
    }

    @Test
    public void pagingCarriesOriginalGenerationRevisionAndDirection() {
        CandidatePage page = page("rime", 5L, 8L, 1, 3, "甲", "乙");

        CandidatePage.PageRequest previous =
                page.pageRequest(CandidatePage.Direction.PREVIOUS);
        CandidatePage.PageRequest next = page.pageRequest(CandidatePage.Direction.NEXT);

        assertEquals(5L, previous.generation());
        assertEquals(8L, previous.pageRevision());
        assertEquals(1, previous.pageIndex());
        assertEquals(CandidatePage.Direction.PREVIOUS, previous.direction());
        assertEquals(CandidatePage.Direction.NEXT, next.direction());
    }

    @Test
    public void pageDefensivelyCopiesItemsAndExposesOnlyDerivedPaging() {
        ArrayList<CandidatePage.Item> source = new ArrayList<>();
        source.add(new CandidatePage.Item("one", "一"));
        CandidatePage page = new CandidatePage("rime", 1L, 2L, 0, 2, source);
        source.add(new CandidatePage.Item("two", "二"));

        assertEquals(1, page.items().size());
        assertFalse(page.hasPreviousPage());
        assertTrue(page.hasNextPage());
        assertThrows(UnsupportedOperationException.class,
                () -> page.items().add(new CandidatePage.Item("three", "三")));
        assertThrows(IllegalStateException.class,
                () -> page.pageRequest(CandidatePage.Direction.PREVIOUS));
    }

    @Test
    public void invalidIdentityBoundsAndControlTextFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> page("../rime", 1L, 1L, 0, 1, "甲"));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage("rime", 0L, 1L, 0, 1,
                        List.of(new CandidatePage.Item("c0", "甲"))));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage("rime", 1L, 1L, 2, 2,
                        List.of(new CandidatePage.Item("c0", "甲"))));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage("rime", 1L, 1L, 0, 1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage.Item("c0", "line\nbreak"));
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage.Item("../id", "甲"));
    }

    @Test
    public void duplicateIdsAndOversizedPagesFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new CandidatePage(
                "rime",
                1L,
                1L,
                0,
                1,
                List.of(
                        new CandidatePage.Item("same", "甲"),
                        new CandidatePage.Item("same", "乙"))));
        List<CandidatePage.Item> tooMany = new ArrayList<>();
        for (int index = 0; index <= CandidatePage.MAXIMUM_CANDIDATES; index++) {
            tooMany.add(new CandidatePage.Item("c" + index, "候选" + index));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage("rime", 1L, 1L, 0, 1, tooMany));

        String oversized = "字".repeat(CandidatePage.MAXIMUM_TEXT_CODE_POINTS + 1);
        assertThrows(IllegalArgumentException.class,
                () -> new CandidatePage.Item("long", oversized));
    }

    @Test
    public void diagnosticsRedactCandidateText() {
        CandidatePage page = page("rime", 2L, 4L, 0, 1, "private-candidate");

        assertFalse(page.toString().contains("private-candidate"));
        assertFalse(page.items().get(0).toString().contains("private-candidate"));
        assertFalse(page.selection(0).toString().contains("private-candidate"));
        assertTrue(page.toString().contains("itemCount=1"));
    }

    private static CandidatePage page(
            String producer,
            long generation,
            long revision,
            int pageIndex,
            int pageCount,
            String... candidates) {
        List<CandidatePage.Item> items = new ArrayList<>();
        for (int index = 0; index < candidates.length; index++) {
            items.add(new CandidatePage.Item("c" + index, candidates[index]));
        }
        return new CandidatePage(
                producer, generation, revision, pageIndex, pageCount, items);
    }
}
