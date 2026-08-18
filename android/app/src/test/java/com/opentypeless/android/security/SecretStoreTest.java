package com.opentypeless.android.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.opentypeless.android.config.SecretRef;

import org.junit.Test;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public final class SecretStoreTest {
    private static final char[] SECRET = "cfg008-test-secret".toCharArray();

    @Test
    public void createUseRotateAndDeleteNeverExposePlaintext() {
        FakeStorage storage = new FakeStorage();
        SecretStore store = store(storage, ids('a', 'b'));

        char[] caller = SECRET.clone();
        SecretRef first = store.create(SecretRef.Kind.ASR, caller);
        assertArrayEquals(SECRET, caller);
        assertFalse(first.toString().contains(new String(SECRET)));

        AtomicReference<char[]> borrowed = new AtomicReference<>();
        store.use(first, value -> {
            assertArrayEquals(SECRET, value);
            borrowed.set(value);
        });
        assertTrue(allZero(borrowed.get()));

        char[] replacement = "rotated-secret".toCharArray();
        SecretRef second = store.rotate(first, replacement);
        assertNotEquals(first, second);
        assertFailure(SecretStore.Failure.SECRET_NOT_FOUND, () -> store.use(first, ignored -> {}));
        store.use(second, value -> assertArrayEquals(replacement, value));
        assertTrue(store.delete(second));
        assertFalse(store.delete(second));
        assertFailure(SecretStore.Failure.SECRET_NOT_FOUND, () -> store.use(second, ignored -> {}));

        String rendered = storage.values.toString() + store + second;
        assertFalse(rendered.contains(new String(SECRET)));
        assertFalse(rendered.contains(new String(replacement)));
    }

    @Test
    public void legacyMigrationIsIdempotentRetainsSourceAndRefreshesExactBindings() {
        FakeStorage storage = new FakeStorage();
        storage.values.put("stt_api_key", storage.protect("old-stt".toCharArray()));
        storage.values.put("llm_api_key", storage.protect("old-llm".toCharArray()));
        SecretStore store = store(storage, ids('c', 'd', 'e'));

        SecretStore.LegacyRefs first = store.migrateLegacy();
        assertEquals(SecretRef.Kind.ASR, first.sttAsr().orElseThrow().kind());
        assertTrue(first.streamingAsr().isEmpty());
        assertEquals(SecretRef.Kind.LLM, first.llm().orElseThrow().kind());
        assertTrue(storage.values.containsKey("stt_api_key"));
        assertTrue(storage.values.containsKey("llm_api_key"));
        assertEquals(1, storage.commits);
        store.use(first.sttAsr().orElseThrow(), value -> assertArrayEquals(
                "old-stt".toCharArray(), value));

        assertEquals(first, store.migrateLegacy());
        assertEquals(1, storage.commits);

        EnumMap<SecretStore.LegacySlot, String> refreshed = new EnumMap<>(
                SecretStore.LegacySlot.class);
        refreshed.put(SecretStore.LegacySlot.STT_ASR, storage.protect("new-stt".toCharArray()));
        refreshed.put(SecretStore.LegacySlot.STREAMING_ASR, storage.protect("streaming".toCharArray()));
        refreshed.put(SecretStore.LegacySlot.LLM, "");
        SecretStore.LegacyRefs second = store.commitLegacyPrepared(refreshed);
        assertEquals(first.sttAsr(), second.sttAsr());
        assertTrue(second.streamingAsr().isPresent());
        assertTrue(second.llm().isEmpty());
        assertEquals(2, storage.commits);
        assertFalse(storage.values.containsKey("llm_api_key"));
        store.use(second.sttAsr().orElseThrow(), value -> assertArrayEquals(
                "new-stt".toCharArray(), value));
    }

    @Test
    public void legacyBindingsCanOnlyRefreshThroughTheExactMigrationBridge() {
        FakeStorage storage = new FakeStorage();
        storage.values.put("stt_api_key", storage.protect("legacy".toCharArray()));
        SecretStore store = store(storage, ids('f', 'g'));
        SecretRef first = store.migrateLegacy().sttAsr().orElseThrow();

        assertFailure(SecretStore.Failure.LEGACY_AUTHORITY,
                () -> store.rotate(first, "replacement".toCharArray()));
        assertFailure(SecretStore.Failure.LEGACY_AUTHORITY, () -> store.delete(first));
        EnumMap<SecretStore.LegacySlot, String> refreshed = new EnumMap<>(
                SecretStore.LegacySlot.class);
        refreshed.put(SecretStore.LegacySlot.STT_ASR, storage.protect("replacement".toCharArray()));
        refreshed.put(SecretStore.LegacySlot.STREAMING_ASR, "");
        refreshed.put(SecretStore.LegacySlot.LLM, "");
        SecretRef updated = store.commitLegacyPrepared(refreshed).sttAsr().orElseThrow();
        assertEquals(first, updated);
        assertEquals(storage.protect("replacement".toCharArray()), storage.values.get("stt_api_key"));
        assertEquals(updated.opaqueId(), storage.values.get("cfg008_binding_stt_asr"));
        store.use(updated, value -> assertArrayEquals("replacement".toCharArray(), value));
    }

    @Test
    public void settingsRollbackRestoresRetiredLegacyIdentityWithoutAllocatingAReplacement() {
        FakeStorage storage = new FakeStorage();
        String oldCiphertext = storage.protect("legacy".toCharArray());
        storage.values.put("stt_api_key", oldCiphertext);
        SecretStore store = store(storage, ids('w', 'x'));
        SecretStore.LegacyRefs before = store.migrateLegacy();
        SecretRef exact = before.sttAsr().orElseThrow();

        EnumMap<SecretStore.LegacySlot, String> cleared = legacyValues("", "", "");
        assertTrue(store.commitLegacyPrepared(cleared).sttAsr().isEmpty());

        EnumMap<SecretStore.LegacySlot, String> previous =
                legacyValues(oldCiphertext, "", "");
        assertEquals(before, store.restoreLegacyPrepared(previous, before));
        store.verifyLegacyPrepared(previous, before);
        store.use(exact, value -> assertArrayEquals("legacy".toCharArray(), value));
        assertEquals(opaque('w'), exact.opaqueId());
    }

    @Test
    public void invalidSecretsAndIdGenerationFailBeforeMutation() {
        FakeStorage storage = new FakeStorage();
        SecretStore store = store(storage, ids('h'));

        assertFailure(SecretStore.Failure.INVALID_INPUT,
                () -> store.create(SecretRef.Kind.ASR, null));
        assertFailure(SecretStore.Failure.INVALID_INPUT,
                () -> store.create(SecretRef.Kind.ASR, new char[0]));
        assertFailure(SecretStore.Failure.INVALID_INPUT,
                () -> store.create(SecretRef.Kind.ASR, "   ".toCharArray()));
        assertFailure(SecretStore.Failure.INVALID_INPUT,
                () -> store.create(SecretRef.Kind.ASR, new char[]{'x', '\uD800'}));
        assertFailure(SecretStore.Failure.INVALID_INPUT,
                () -> store.create(SecretRef.Kind.ASR, "x".repeat(
                        SecretStore.MAX_SECRET_CODE_POINTS + 1).toCharArray()));
        assertEquals(0, storage.commits);

        SecretStore invalidId = new SecretStore(storage, () -> "secret-in-value");
        assertFailure(SecretStore.Failure.ID_GENERATION_FAILED,
                () -> invalidId.create(SecretRef.Kind.ASR, SECRET));
        assertEquals(0, storage.commits);
        SecretStore throwingId = new SecretStore(storage, () -> {
            throw new IllegalStateException("secret-in-error");
        });
        SecretStore.SecretStoreException failure = assertThrows(
                SecretStore.SecretStoreException.class,
                () -> throwingId.create(SecretRef.Kind.ASR, SECRET));
        assertEquals(SecretStore.Failure.ID_GENERATION_FAILED, failure.failure());
        assertFalse(failure.toString().contains("secret-in-error"));
    }

    @Test
    public void unknownPartialCorruptAndDuplicateBindingStoresFailClosed() {
        FakeStorage unknown = new FakeStorage();
        unknown.values.put("cfg008_format_version", "2");
        unknown.values.put("cfg008_migration_version", "1");
        unknown.values.put("cfg008_legacy_backup_retained", "true");
        assertFailure(SecretStore.Failure.UNKNOWN_VERSION,
                () -> store(unknown, ids('i')).migrateLegacy());
        assertEquals(0, unknown.commits);

        FakeStorage partial = new FakeStorage();
        partial.values.put("cfg008_format_version", "1");
        assertFailure(SecretStore.Failure.PARTIAL_STORE,
                () -> store(partial, ids('j')).migrateLegacy());

        FakeStorage duplicate = validTarget('k', SecretRef.Kind.ASR, "value");
        String id = opaque('k');
        duplicate.values.put("cfg008_binding_stt_asr", id);
        duplicate.values.put("cfg008_binding_streaming_asr", id);
        assertFailure(SecretStore.Failure.CORRUPT_STORE,
                () -> store(duplicate, ids('l')).migrateLegacy());

        FakeStorage wrongType = new FakeStorage();
        wrongType.values.put("stt_api_key", 7);
        assertFailure(SecretStore.Failure.CORRUPT_SOURCE,
                () -> store(wrongType, ids('m')).migrateLegacy());
    }

    @Test
    public void commitReadbackKeyAndCallbackFailuresAreStableAndRedacted() {
        FakeStorage commit = new FakeStorage();
        commit.failCommit = true;
        assertFailure(SecretStore.Failure.COMMIT_FAILED,
                () -> store(commit, ids('n')).create(SecretRef.Kind.ASR, SECRET));

        FakeStorage readback = new FakeStorage();
        readback.dropWrites = true;
        assertFailure(SecretStore.Failure.READBACK_FAILED,
                () -> store(readback, ids('o')).create(SecretRef.Kind.ASR, SECRET));

        FakeStorage alteredReadback = new FakeStorage();
        alteredReadback.alterEntryWrites = true;
        assertFailure(SecretStore.Failure.READBACK_FAILED,
                () -> store(alteredReadback, ids('t')).create(SecretRef.Kind.ASR, SECRET));

        FakeStorage retainedOld = new FakeStorage();
        SecretStore rotating = store(retainedOld, ids('u', 'v'));
        SecretRef old = rotating.create(SecretRef.Kind.ASR, SECRET);
        retainedOld.ignoreRemovals = true;
        assertFailure(SecretStore.Failure.READBACK_FAILED,
                () -> rotating.rotate(old, "changed".toCharArray()));

        FakeStorage key = new FakeStorage();
        key.failProtect = true;
        assertFailure(SecretStore.Failure.KEY_UNAVAILABLE,
                () -> store(key, ids('p')).create(SecretRef.Kind.ASR, SECRET));

        FakeStorage use = new FakeStorage();
        SecretStore store = store(use, ids('q'));
        SecretRef ref = store.create(SecretRef.Kind.ASR, SECRET);
        use.failDecrypt = true;
        assertFailure(SecretStore.Failure.KEY_UNAVAILABLE, () -> store.use(ref, ignored -> {}));
        use.failDecrypt = false;
        use.decryptedOverride = "x".repeat(
                SecretStore.MAX_SECRET_CODE_POINTS + 1).toCharArray();
        assertFailure(SecretStore.Failure.CORRUPT_STORE,
                () -> store.use(ref, ignored -> {}));
        assertTrue(allZero(use.lastDecrypted));
        use.decryptedOverride = null;
        SecretStore.SecretStoreException failure = assertThrows(
                SecretStore.SecretStoreException.class,
                () -> store.use(ref, ignored -> {
                    throw new IllegalStateException(new String(SECRET));
                }));
        assertEquals(SecretStore.Failure.USE_FAILED, failure.failure());
        assertFalse(failure.toString().contains(new String(SECRET)));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(null, failure.getCause());
    }

    @Test
    public void exactEntryLimitAndOpaqueCollisionHandlingAreBounded() {
        FakeStorage storage = new FakeStorage();
        Queue<String> generated = new ArrayDeque<>();
        for (int index = 0; index < SecretStore.MAX_ENTRIES; index++) {
            generated.add("sec_" + String.format("%032x", index + 1));
        }
        SecretStore store = new SecretStore(storage, generated::remove);
        for (int index = 0; index < SecretStore.MAX_ENTRIES; index++) {
            store.create(SecretRef.Kind.CONNECTOR, new char[]{'x', (char) ('a' + (index % 26))});
        }
        int commits = storage.commits;
        assertFailure(SecretStore.Failure.STORE_LIMIT_EXCEEDED,
                () -> store.create(SecretRef.Kind.CONNECTOR, SECRET));
        assertEquals(commits, storage.commits);

        FakeStorage collision = new FakeStorage();
        SecretStore colliding = store(collision, ids('r', 'r', 's'));
        SecretRef first = colliding.create(SecretRef.Kind.ASR, SECRET);
        SecretRef second = colliding.create(SecretRef.Kind.ASR, "other".toCharArray());
        assertNotEquals(first, second);
    }

    @Test
    public void modelAndStoreShapeCannotCarryParcelableSerializableOrPlaintextFields() {
        assertFalse(Serializable.class.isAssignableFrom(SecretStore.class));
        assertFalse(Serializable.class.isAssignableFrom(SecretStore.LegacyRefs.class));
        for (Field field : SecretStore.class.getDeclaredFields()) {
            assertFalse(field.getType().equals(char[].class));
            if (field.getType().equals(String.class)) {
                assertTrue(java.lang.reflect.Modifier.isStatic(field.getModifiers()));
            }
        }
        String typeNames = Arrays.toString(SecretStore.class.getDeclaredClasses());
        assertFalse(typeNames.contains("android.os.Bundle"));
        assertFalse(typeNames.contains("android.content.Intent"));
    }

    private static SecretStore store(FakeStorage storage, Queue<String> ids) {
        return new SecretStore(storage, ids::remove);
    }

    private static Queue<String> ids(char... suffixes) {
        Queue<String> values = new ArrayDeque<>();
        for (char suffix : suffixes) values.add(opaque(suffix));
        return values;
    }

    private static String opaque(char suffix) {
        return "sec_" + String.valueOf(suffix).repeat(32);
    }

    private static EnumMap<SecretStore.LegacySlot, String> legacyValues(
            String stt,
            String streaming,
            String llm) {
        EnumMap<SecretStore.LegacySlot, String> values =
                new EnumMap<>(SecretStore.LegacySlot.class);
        values.put(SecretStore.LegacySlot.STT_ASR, stt);
        values.put(SecretStore.LegacySlot.STREAMING_ASR, streaming);
        values.put(SecretStore.LegacySlot.LLM, llm);
        return values;
    }

    private static FakeStorage validTarget(char suffix, SecretRef.Kind kind, String plaintext) {
        FakeStorage storage = new FakeStorage();
        String id = opaque(suffix);
        storage.values.put("cfg008_format_version", "1");
        storage.values.put("cfg008_migration_version", "1");
        storage.values.put("cfg008_legacy_backup_retained", "true");
        storage.values.put("cfg008_entry_" + id, storage.protect(plaintext.toCharArray()));
        storage.values.put("cfg008_kind_" + id, kind.name());
        return storage;
    }

    private static void assertFailure(SecretStore.Failure expected, Runnable action) {
        SecretStore.SecretStoreException failure = assertThrows(
                SecretStore.SecretStoreException.class,
                action::run);
        assertEquals(expected, failure.failure());
        assertEquals("Secret store operation failed", failure.getMessage());
        assertEquals(null, failure.getCause());
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static final class FakeStorage implements SecretStore.Storage {
        private final Map<String, Object> values = new HashMap<>();
        private int commits;
        private boolean failCommit;
        private boolean dropWrites;
        private boolean alterEntryWrites;
        private boolean ignoreRemovals;
        private boolean failProtect;
        private boolean failDecrypt;
        private char[] decryptedOverride;
        private char[] lastDecrypted;

        @Override
        public Map<String, ?> snapshot() {
            return Map.copyOf(values);
        }

        @Override
        public String protect(char[] plaintext) {
            if (failProtect) throw new IllegalStateException("secret-protect-failure");
            return "enc." + Base64.getEncoder().encodeToString(
                    new String(plaintext).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public char[] decrypt(String encoded) {
            if (failDecrypt) throw new IllegalStateException("secret-decrypt-failure");
            if (decryptedOverride != null) {
                lastDecrypted = decryptedOverride.clone();
                return lastDecrypted;
            }
            if (!encoded.startsWith("enc.")) throw new IllegalArgumentException("bad encoded");
            lastDecrypted = new String(
                    Base64.getDecoder().decode(encoded.substring(4)),
                    StandardCharsets.UTF_8).toCharArray();
            return lastDecrypted;
        }

        @Override
        public void commit(Map<String, String> replacements, Set<String> removals) {
            if (failCommit) throw new IllegalStateException("secret-commit-failure");
            commits++;
            if (dropWrites) return;
            if (!ignoreRemovals) removals.forEach(values::remove);
            values.putAll(replacements);
            if (alterEntryWrites) {
                replacements.keySet().stream()
                        .filter(key -> key.startsWith("cfg008_entry_"))
                        .forEach(key -> values.put(key, "enc.Y29ycnVwdGVk"));
            }
        }
    }
}
