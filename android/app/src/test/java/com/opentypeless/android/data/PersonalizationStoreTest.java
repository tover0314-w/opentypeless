package com.opentypeless.android.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PersonalizationStoreTest {
    @Test
    public void identityUsesNfkcAndLocaleRootCaseMapping() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("tr-TR"));
        try {
            assertEquals(
                    PersonalizationStore.identity("opentypeless"),
                    PersonalizationStore.identity("  ＯＰＥＮＴＹＰＥＬＥＳＳ  "));
            assertEquals("kelvin", PersonalizationStore.identity("KELVIN"));
            assertEquals("i", PersonalizationStore.identity("I"));
            assertEquals(
                    PersonalizationStore.termIdentity("Token", "COM.EXAMPLE.APP"),
                    PersonalizationStore.termIdentity("ＴＯＫＥＮ", "com.example.app"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void importDocumentLimitCountsUtf8Bytes() {
        PersonalizationStore.validateImportDocument("a".repeat(PersonalizationStore.MAX_IMPORT_BYTES));

        IllegalArgumentException ascii = assertThrows(
                IllegalArgumentException.class,
                () -> PersonalizationStore.validateImportDocument(
                        "a".repeat(PersonalizationStore.MAX_IMPORT_BYTES + 1)));
        assertTrue(ascii.getMessage().contains("1 MiB"));

        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalizationStore.validateImportDocument(
                        "你".repeat(PersonalizationStore.MAX_IMPORT_BYTES / 3 + 1)));
    }

    @Test
    public void previewDeduplicatesNfkcRowsAgainstDatabaseAndWithinFile() {
        PersonalizationStore.ImportTerm fullWidth = term("ＯＰＥＮＴＹＰＥＬＥＳＳ", "");
        PersonalizationStore.ImportTerm talkMore = term("TalkMore", "com.chat");
        PersonalizationStore.ImportPlan plan = new PersonalizationStore.ImportPlan(
                List.of(fullWidth, talkMore, term("ＴＡＬＫＭＯＲＥ", "COM.CHAT")),
                List.of(
                        correction("open type less", "OpenTypeless", ""),
                        correction(" OPEN TYPE LESS ", "ＯＰＥＮＴＹＰＥＬＥＳＳ", "")),
                List.of(new PersonalizationStore.ImportIssue("terms", 4, "Term is required")));
        PersonalizationStore.ExistingImportState existing = state(
                Set.of(PersonalizationStore.termIdentity("OpenTypeless", "")),
                Set.of(),
                1,
                0);

        PersonalizationStore.ImportPreview preview =
                PersonalizationStore.previewPlan(plan, existing);

        assertEquals(1, preview.acceptedTerms());
        assertEquals(2, preview.duplicateTerms());
        assertEquals(1, preview.acceptedCorrections());
        assertEquals(1, preview.duplicateCorrections());
        assertEquals(1, preview.invalidRows());
        assertTrue(preview.fitsCapacity());
        assertEquals(List.of("TalkMore"), preview.termSamples().stream()
                .map(PersonalizationStore.ImportTerm::canonical)
                .toList());
        assertEquals(List.of("open type less->OpenTypeless"),
                preview.correctionSamples().stream()
                        .map(row -> row.pattern() + "->" + row.replacement())
                        .toList());
        assertThrows(
                UnsupportedOperationException.class,
                () -> preview.termSamples().add(term("hidden mutation", "")));
    }

    @Test
    public void previewSamplesAreBoundedToTheRowsThatWouldActuallyImport() {
        List<PersonalizationStore.ImportTerm> rows = new ArrayList<>();
        for (int index = 0; index < PersonalizationStore.MAX_IMPORT_PREVIEW_SAMPLES + 2; index++) {
            rows.add(term("term-" + index, ""));
        }
        PersonalizationStore.ImportPreview preview = PersonalizationStore.previewPlan(
                new PersonalizationStore.ImportPlan(rows, List.of(), List.of()),
                state(Set.of(PersonalizationStore.termIdentity("term-0", "")), Set.of(), 1, 0));

        assertEquals(PersonalizationStore.MAX_IMPORT_PREVIEW_SAMPLES, preview.termSamples().size());
        assertEquals("term-1", preview.termSamples().get(0).canonical());
        assertEquals("term-5", preview.termSamples().get(4).canonical());
        assertEquals(PersonalizationStore.MAX_IMPORT_PREVIEW_SAMPLES + 1, preview.acceptedTerms());
    }

    @Test
    public void parserBuildsAnImmutablePlanAndReportsInvalidRows() {
        String json = """
                {
                  "format": "opentypeless-personalization-v1",
                  "terms": [
                    {"canonical": "OpenTypeless", "aliases": "open type less"},
                    {"canonical": 42},
                    "not-an-object"
                  ],
                  "corrections": [
                    {"pattern": "open type list", "replacement": "OpenTypeless"}
                  ]
                }
                """;

        PersonalizationStore.ImportPlan plan = PersonalizationStore.parseImportPlan(json);

        assertEquals(1, plan.terms().size());
        assertEquals(1, plan.corrections().size());
        assertEquals(2, plan.issues().size());
        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.terms().add(term("mutated", "")));
    }

    @Test
    public void parserAcceptsDesktopDictionaryV1AndPreservesAndroidExtensions() {
        String json = """
                {
                  "format": "opentypeless_dictionary",
                  "version": 1,
                  "dictionary": [
                    {"word": "OpenTypeless", "pronunciation": "open typeless",
                     "aliases": "open type less", "appScope": "com.chat", "enabled": false},
                    "TalkMore"
                  ],
                  "correctionRules": [
                    {"wrongPhrase": "open type list", "correctedPhrase": "OpenTypeless"}
                  ]
                }
                """;

        PersonalizationStore.ImportPlan plan = PersonalizationStore.parseImportPlan(json);

        assertEquals(2, plan.terms().size());
        assertEquals("OpenTypeless", plan.terms().get(0).canonical());
        assertEquals("open typeless", plan.terms().get(0).pronunciation());
        assertEquals("open type less", plan.terms().get(0).aliases());
        assertEquals("com.chat", plan.terms().get(0).appScope());
        assertFalse(plan.terms().get(0).enabled());
        assertEquals("TalkMore", plan.terms().get(1).canonical());
        assertEquals(1, plan.corrections().size());
        assertEquals("OpenTypeless", plan.corrections().get(0).replacement());
        assertTrue(plan.issues().isEmpty());
    }

    @Test
    public void parserAcceptsUnmarkedDesktopBackupSubsetButRejectsWrongVersion() {
        PersonalizationStore.ImportPlan subset = PersonalizationStore.parseImportPlan(
                "{\"dictionary\":[{\"word\":\"Portable Name\"}],"
                        + "\"correction_rules\":[{\"pattern\":\"wrong\","
                        + "\"replacement\":\"right\"}]}");

        assertEquals(1, subset.terms().size());
        assertEquals(1, subset.corrections().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalizationStore.parseImportPlan(
                        "{\"format\":\"opentypeless_dictionary\",\"version\":2,"
                                + "\"dictionary\":[]}"));
    }

    @Test
    public void parserRejectsTermsWithPathologicalAliasCounts() {
        String aliases = java.util.stream.IntStream.rangeClosed(
                        1,
                        PersonalTerm.MAX_ALIASES + 1)
                .mapToObj(index -> "alias" + index)
                .collect(java.util.stream.Collectors.joining(","));
        String json = "{\"format\":\"opentypeless-personalization-v1\","
                + "\"terms\":[{\"canonical\":\"OpenTypeless\",\"aliases\":\""
                + aliases
                + "\"}]}";

        PersonalizationStore.ImportPlan plan = PersonalizationStore.parseImportPlan(json);

        assertTrue(plan.terms().isEmpty());
        assertEquals(1, plan.issues().size());
        assertTrue(plan.issues().get(0).message().contains("limited"));
    }

    @Test
    public void previewUsesAcceptedUniqueRowsForEachTableCapacity() {
        List<PersonalizationStore.ImportTerm> duplicateRows = new ArrayList<>();
        for (int index = 0; index < 3_000; index++) duplicateRows.add(term("TalkMore", ""));
        PersonalizationStore.ImportPlan duplicates = new PersonalizationStore.ImportPlan(
                duplicateRows,
                List.of(correction("wrong", "right", "")),
                List.of());
        PersonalizationStore.ImportPreview fits = PersonalizationStore.previewPlan(
                duplicates,
                state(Set.of(), Set.of(), PersonalizationStore.MAX_TERMS - 1,
                        PersonalizationStore.MAX_CORRECTIONS - 1));

        assertEquals(1, fits.acceptedTerms());
        assertEquals(2_999, fits.duplicateTerms());
        assertEquals(1, fits.acceptedCorrections());
        assertTrue(fits.fitsCapacity());

        PersonalizationStore.ImportPreview termFull = PersonalizationStore.previewPlan(
                new PersonalizationStore.ImportPlan(
                        List.of(term("Another", "")),
                        List.of(correction("one", "two", "")),
                        List.of()),
                state(Set.of(), Set.of(), PersonalizationStore.MAX_TERMS, 0));
        assertFalse(termFull.fitsCapacity());
        assertEquals(0, termFull.remainingTermCapacity());
        assertEquals(1, termFull.acceptedCorrections());
    }

    @Test
    public void commitIsAtomicWhenAnyInsertFails() {
        FakeTransaction transaction = new FakeTransaction(state(Set.of(), Set.of(), 0, 0));
        transaction.failOnCanonical = "boom";
        PersonalizationStore.ImportPlan plan = new PersonalizationStore.ImportPlan(
                List.of(term("first", ""), term("boom", "")),
                List.of(correction("wrong", "right", "")),
                List.of());

        assertThrows(
                IllegalStateException.class,
                () -> PersonalizationStore.commitPlan(plan, transaction));

        assertTrue(transaction.began);
        assertTrue(transaction.ended);
        assertFalse(transaction.successful);
        assertTrue(transaction.committedTerms.isEmpty());
        assertTrue(transaction.committedCorrections.isEmpty());
    }

    @Test
    public void commitRollsBackBeforeInsertsWhenEitherTableIsOverCapacity() {
        FakeTransaction transaction = new FakeTransaction(state(
                Set.of(), Set.of(), PersonalizationStore.MAX_TERMS, 0));
        PersonalizationStore.ImportPlan plan = new PersonalizationStore.ImportPlan(
                List.of(term("one too many", "")),
                List.of(correction("wrong", "right", "")),
                List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> PersonalizationStore.commitPlan(plan, transaction));

        assertTrue(transaction.began);
        assertTrue(transaction.ended);
        assertFalse(transaction.successful);
        assertTrue(transaction.committedTerms.isEmpty());
        assertTrue(transaction.committedCorrections.isEmpty());
    }

    @Test
    public void commitRechecksStateAndCommitsOnlyUniqueRows() {
        FakeTransaction transaction = new FakeTransaction(state(
                Set.of(PersonalizationStore.termIdentity("OpenTypeless", "")),
                Set.of(),
                1,
                0));
        PersonalizationStore.ImportPlan plan = new PersonalizationStore.ImportPlan(
                List.of(term("ＯＰＥＮＴＹＰＥＬＥＳＳ", ""), term("TalkMore", "")),
                List.of(correction("talk more", "TalkMore", "")),
                List.of());

        PersonalizationStore.ImportReport report =
                PersonalizationStore.commitPlan(plan, transaction);

        assertTrue(transaction.successful);
        assertTrue(transaction.ended);
        assertEquals(1, report.importedTerms());
        assertEquals(1, report.duplicateTerms());
        assertEquals(1, report.importedCorrections());
        assertEquals(List.of("TalkMore"), transaction.committedTerms);
        assertEquals(List.of("talk more->TalkMore"), transaction.committedCorrections);
    }

    @Test
    public void immutablePlanRejectsTooManyRowsBeforeTransaction() {
        List<PersonalizationStore.ImportTerm> rows = new ArrayList<>();
        for (int index = 0; index <= PersonalizationStore.MAX_IMPORT_ROWS; index++) {
            rows.add(term("term-" + index, ""));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> new PersonalizationStore.ImportPlan(rows, List.of(), List.of()));
    }

    private static PersonalizationStore.ImportTerm term(String canonical, String scope) {
        return new PersonalizationStore.ImportTerm(canonical, "", "", scope, true);
    }

    private static PersonalizationStore.ImportCorrection correction(
            String pattern, String replacement, String scope) {
        return new PersonalizationStore.ImportCorrection(pattern, replacement, scope, true);
    }

    private static PersonalizationStore.ExistingImportState state(
            Set<String> terms,
            Set<String> corrections,
            int termCount,
            int correctionCount) {
        return new PersonalizationStore.ExistingImportState(
                terms, corrections, termCount, correctionCount);
    }

    private static final class FakeTransaction implements PersonalizationStore.ImportTransaction {
        private final PersonalizationStore.ExistingImportState existing;
        private final List<String> pendingTerms = new ArrayList<>();
        private final List<String> pendingCorrections = new ArrayList<>();
        private final List<String> committedTerms = new ArrayList<>();
        private final List<String> committedCorrections = new ArrayList<>();
        private String failOnCanonical;
        private boolean began;
        private boolean ended;
        private boolean successful;

        private FakeTransaction(PersonalizationStore.ExistingImportState existing) {
            this.existing = existing;
        }

        @Override
        public void begin() {
            began = true;
        }

        @Override
        public PersonalizationStore.ExistingImportState readExisting() {
            return existing;
        }

        @Override
        public void insertTerm(PersonalizationStore.ImportTerm term) {
            if (term.canonical().equals(failOnCanonical)) throw new IllegalStateException("boom");
            pendingTerms.add(term.canonical());
        }

        @Override
        public void insertCorrection(PersonalizationStore.ImportCorrection correction) {
            pendingCorrections.add(correction.pattern() + "->" + correction.replacement());
        }

        @Override
        public void setSuccessful() {
            successful = true;
        }

        @Override
        public void end() {
            ended = true;
            if (successful) {
                committedTerms.addAll(pendingTerms);
                committedCorrections.addAll(pendingCorrections);
            }
            pendingTerms.clear();
            pendingCorrections.clear();
        }
    }
}
