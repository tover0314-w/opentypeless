package com.opentypeless.android.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class AppPickerModelTest {
    @Test
    public void entriesAreSortedDeduplicatedAndImmutable() {
        AppPickerModel model = new AppPickerModel(List.of(
                entry("Zulu", "com.example.zulu"),
                entry("alpha alternate", "com.example.alpha"),
                entry("Alpha", "com.example.alpha"),
                entry("Beta", "com.example.beta")));

        assertEquals(List.of(
                entry("Alpha", "com.example.alpha"),
                entry("Beta", "com.example.beta"),
                entry("Zulu", "com.example.zulu")), model.entries());
        assertThrows(UnsupportedOperationException.class,
                () -> model.entries().add(entry("Later", "com.example.later")));
    }

    @Test
    public void searchMatchesLabelOrPackageWithoutChangingOrder() {
        AppPickerModel model = new AppPickerModel(List.of(
                entry("思源笔记", "org.b3log.siyuan"),
                entry("Chrome", "com.android.chrome"),
                entry("飞书", "com.ss.android.lark")));

        assertEquals(List.of(entry("Chrome", "com.android.chrome")), model.search("CHROME"));
        assertEquals(List.of(entry("思源笔记", "org.b3log.siyuan")), model.search("b3log"));
        assertEquals(model.entries(), model.search("  "));
        assertTrue(model.search("missing").isEmpty());
    }

    @Test
    public void emptyLabelFallsBackToValidatedPackageName() {
        AppPickerModel.Entry entry = entry("   ", "com.example.app");
        assertEquals("com.example.app", entry.label());
    }

    @Test
    public void entryAndQueryBoundsFailClosed() {
        List<AppPickerModel.Entry> tooMany = new ArrayList<>();
        for (int index = 0; index <= AppPickerModel.MAX_ENTRIES; index++) {
            tooMany.add(entry("App " + index, "com.example.app" + index));
        }
        assertThrows(IllegalArgumentException.class, () -> new AppPickerModel(tooMany));
        assertThrows(IllegalArgumentException.class,
                () -> entry("x".repeat(AppPickerModel.MAX_LABEL_CODE_POINTS + 1),
                        "com.example.app"));

        AppPickerModel model = new AppPickerModel(List.of(entry("App", "com.example.app")));
        assertThrows(IllegalArgumentException.class,
                () -> model.search("x".repeat(AppPickerModel.MAX_QUERY_CODE_POINTS + 1)));
    }

    @Test
    public void malformedUnicodeControlsAndPackagesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> entry("bad\uD800", "com.example.app"));
        assertThrows(IllegalArgumentException.class,
                () -> entry("bad\nlabel", "com.example.app"));
        assertThrows(IllegalArgumentException.class,
                () -> entry("App", "not-a-package"));

        AppPickerModel model = new AppPickerModel(List.of(entry("App", "com.example.app")));
        assertThrows(IllegalArgumentException.class, () -> model.search("bad\uDC00"));
        assertThrows(IllegalArgumentException.class, () -> model.search("bad\nquery"));
        assertThrows(NullPointerException.class, () -> model.search(null));
    }

    @Test
    public void diagnosticsDoNotExposeLabelsPackagesOrQueries() {
        AppPickerModel.Entry entry = entry("private-label", "com.private.application");
        AppPickerModel model = new AppPickerModel(List.of(entry));

        String diagnostics = model + " " + entry;
        assertFalse(diagnostics.contains("private-label"));
        assertFalse(diagnostics.contains("com.private.application"));
        assertTrue(diagnostics.contains("<redacted>"));
    }

    private static AppPickerModel.Entry entry(String label, String packageName) {
        return new AppPickerModel.Entry(label, packageName);
    }
}
