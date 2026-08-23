package com.opentypeless.android.keyboard.candidate;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, engine-independent candidate page for the keyboard Shell.
 *
 * <p>The page carries a producer generation and page revision so a later engine can reject stale
 * selection and paging callbacks without trusting a visible index alone. It has no Android,
 * editor, JNI, storage or network capability.
 */
public final class CandidatePage {
    public static final int MAXIMUM_CANDIDATES = 16;
    public static final int MAXIMUM_PAGES = 128;
    public static final int MAXIMUM_TEXT_CODE_POINTS = 256;
    public static final int MAXIMUM_ID_LENGTH = 64;

    private static final Pattern PRODUCER_ID = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final Pattern CANDIDATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");

    public enum Direction {
        PREVIOUS,
        NEXT
    }

    public record Item(String id, String text) {
        public Item {
            id = requireCandidateId(id);
            text = requireCandidateText(text);
        }

        @Override
        public String toString() {
            return "CandidatePage.Item{id=" + id + ", text=<redacted>}";
        }
    }

    public record Selection(
            String producerId,
            long generation,
            long pageRevision,
            int pageIndex,
            int candidateIndex,
            String candidateId,
            String expectedText) {
        public Selection {
            producerId = requireProducerId(producerId);
            requirePositive(generation, "generation");
            requirePositive(pageRevision, "pageRevision");
            if (pageIndex < 0 || pageIndex >= MAXIMUM_PAGES) {
                throw new IllegalArgumentException("pageIndex is out of range");
            }
            if (candidateIndex < 0 || candidateIndex >= MAXIMUM_CANDIDATES) {
                throw new IllegalArgumentException("candidateIndex is out of range");
            }
            candidateId = requireCandidateId(candidateId);
            expectedText = requireCandidateText(expectedText);
        }

        @Override
        public String toString() {
            return "CandidatePage.Selection{producerId=" + producerId
                    + ", generation=" + generation
                    + ", pageRevision=" + pageRevision
                    + ", pageIndex=" + pageIndex
                    + ", candidateIndex=" + candidateIndex
                    + ", candidateId=" + candidateId
                    + ", expectedText=<redacted>}";
        }
    }

    public record PageRequest(
            String producerId,
            long generation,
            long pageRevision,
            int pageIndex,
            Direction direction) {
        public PageRequest {
            producerId = requireProducerId(producerId);
            requirePositive(generation, "generation");
            requirePositive(pageRevision, "pageRevision");
            if (pageIndex < 0 || pageIndex >= MAXIMUM_PAGES) {
                throw new IllegalArgumentException("pageIndex is out of range");
            }
            direction = Objects.requireNonNull(direction, "direction");
        }
    }

    private final String producerId;
    private final long generation;
    private final long pageRevision;
    private final int pageIndex;
    private final int pageCount;
    private final List<Item> items;

    public CandidatePage(
            String producerId,
            long generation,
            long pageRevision,
            int pageIndex,
            int pageCount,
            List<Item> items) {
        this.producerId = requireProducerId(producerId);
        requirePositive(generation, "generation");
        requirePositive(pageRevision, "pageRevision");
        if (pageCount <= 0 || pageCount > MAXIMUM_PAGES) {
            throw new IllegalArgumentException("pageCount must be 1.." + MAXIMUM_PAGES);
        }
        if (pageIndex < 0 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("pageIndex must be within pageCount");
        }
        Objects.requireNonNull(items, "items");
        if (items.isEmpty() || items.size() > MAXIMUM_CANDIDATES) {
            throw new IllegalArgumentException(
                    "items must contain 1.." + MAXIMUM_CANDIDATES + " candidates");
        }
        List<Item> copy = List.copyOf(items);
        Set<String> ids = new HashSet<>();
        for (Item item : copy) {
            Objects.requireNonNull(item, "candidate item");
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("candidate ids must be unique within a page");
            }
        }
        this.generation = generation;
        this.pageRevision = pageRevision;
        this.pageIndex = pageIndex;
        this.pageCount = pageCount;
        this.items = copy;
    }

    public String producerId() {
        return producerId;
    }

    public long generation() {
        return generation;
    }

    public long pageRevision() {
        return pageRevision;
    }

    public int pageIndex() {
        return pageIndex;
    }

    public int pageCount() {
        return pageCount;
    }

    public List<Item> items() {
        return items;
    }

    public boolean hasPreviousPage() {
        return pageIndex > 0;
    }

    public boolean hasNextPage() {
        return pageIndex + 1 < pageCount;
    }

    public Selection selection(int candidateIndex) {
        if (candidateIndex < 0 || candidateIndex >= items.size()) {
            throw new IllegalArgumentException("candidateIndex is not present in this page");
        }
        Item item = items.get(candidateIndex);
        return new Selection(
                producerId,
                generation,
                pageRevision,
                pageIndex,
                candidateIndex,
                item.id(),
                item.text());
    }

    public PageRequest pageRequest(Direction direction) {
        Objects.requireNonNull(direction, "direction");
        if (direction == Direction.PREVIOUS && !hasPreviousPage()) {
            throw new IllegalStateException("no previous page");
        }
        if (direction == Direction.NEXT && !hasNextPage()) {
            throw new IllegalStateException("no next page");
        }
        return new PageRequest(producerId, generation, pageRevision, pageIndex, direction);
    }

    @Override
    public String toString() {
        return "CandidatePage{producerId=" + producerId
                + ", generation=" + generation
                + ", pageRevision=" + pageRevision
                + ", pageIndex=" + pageIndex
                + ", pageCount=" + pageCount
                + ", itemCount=" + items.size()
                + ", text=<redacted>}";
    }

    private static String requireProducerId(String value) {
        if (value == null || !PRODUCER_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid producerId");
        }
        return value;
    }

    private static String requireCandidateId(String value) {
        if (value == null || !CANDIDATE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid candidate id");
        }
        return value;
    }

    private static String requireCandidateText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("candidate text must not be blank");
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints > MAXIMUM_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("candidate text is too long");
        }
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("candidate text contains a control character");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
    }

    private static void requirePositive(long value, String name) {
        if (value <= 0L) throw new IllegalArgumentException(name + " must be positive");
    }
}
