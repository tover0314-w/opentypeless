package com.opentypeless.android.security;

import android.content.Context;

import com.opentypeless.android.config.SecretRef;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Process-local authority for encrypted credentials identified only by {@link SecretRef}.
 *
 * <p>The store never returns plaintext. A caller may use a credential only inside a synchronous
 * callback whose temporary character buffer is wiped on return. The legacy bridge exists solely
 * so CFG-008 can shadow the three Android 0.2 credential slots without making the new store the
 * runtime configuration authority before CFG-011.
 */
public final class SecretStore {
    public static final int FORMAT_VERSION = 1;
    public static final int MIGRATION_VERSION = 1;
    public static final int MAX_SECRET_CODE_POINTS = 4_096;
    public static final int MAX_ENTRIES = 64;

    private static final int MAX_STORED_VALUE_UTF16_UNITS = 32_768;
    private static final int MAX_ID_ATTEMPTS = 8;
    private static final String TARGET_PREFIX = "cfg008_";
    private static final String FORMAT_KEY = TARGET_PREFIX + "format_version";
    private static final String MIGRATION_KEY = TARGET_PREFIX + "migration_version";
    private static final String BACKUP_KEY = TARGET_PREFIX + "legacy_backup_retained";
    private static final String ENTRY_PREFIX = TARGET_PREFIX + "entry_";
    private static final String KIND_PREFIX = TARGET_PREFIX + "kind_";
    private static final String BINDING_PREFIX = TARGET_PREFIX + "binding_";
    private static final Object STORE_LOCK = new Object();

    private final Storage storage;
    private final IdSource idSource;

    public SecretStore(Context context) {
        this(
                new SecureStorage(new SecurePreferences(context)),
                () -> "sec_" + UUID.randomUUID().toString().replace("-", ""));
    }

    SecretStore(Storage storage, IdSource idSource) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.idSource = Objects.requireNonNull(idSource, "idSource");
    }

    /** Creates an unbound credential entry and returns only its opaque identity. */
    public SecretRef create(SecretRef.Kind kind, char[] secret) {
        Objects.requireNonNull(kind, "kind");
        char[] copy = validatedSecretCopy(secret);
        try {
            synchronized (STORE_LOCK) {
                StoreState before = inspect(storage.snapshot());
                if (before.references().size() >= MAX_ENTRIES) {
                    throw failure(Failure.STORE_LIMIT_EXCEEDED);
                }
                SecretRef reference = newReference(kind, before.references());
                String prepared = protect(copy);
                Map<String, String> values = baseTargetValues();
                values.put(entryKey(reference), prepared);
                values.put(kindKey(reference), kind.name());
                commitAndVerify(values, Set.of(), reference, prepared);
                return reference;
            }
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /** Atomically replaces an exact entry with a fresh identity and retires the old identity. */
    public SecretRef rotate(SecretRef current, char[] replacement) {
        Objects.requireNonNull(current, "current");
        char[] copy = validatedSecretCopy(replacement);
        try {
            synchronized (STORE_LOCK) {
                StoreState before = inspect(storage.snapshot());
                requirePresent(before, current);
                if (before.bindings().containsValue(current)) {
                    throw failure(Failure.LEGACY_AUTHORITY);
                }
                SecretRef next = newReference(current.kind(), before.references());
                String prepared = protect(copy);
                Map<String, String> values = baseTargetValues();
                values.put(entryKey(next), prepared);
                values.put(kindKey(next), next.kind().name());
                Set<String> removals = new HashSet<>();
                removals.add(entryKey(current));
                removals.add(kindKey(current));
                commitAndVerify(values, removals, next, prepared);
                return next;
            }
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /** Removes an exact unbound entry; legacy bindings remain under the migration bridge. */
    public boolean delete(SecretRef reference) {
        Objects.requireNonNull(reference, "reference");
        synchronized (STORE_LOCK) {
            StoreState before = inspect(storage.snapshot());
            if (!before.references().contains(reference)) return false;
            if (before.bindings().containsValue(reference)) {
                throw failure(Failure.LEGACY_AUTHORITY);
            }
            Map<String, String> values = baseTargetValues();
            Set<String> removals = new HashSet<>();
            removals.add(entryKey(reference));
            removals.add(kindKey(reference));
            commit(values, removals);
            StoreState after = inspect(storage.snapshot());
            if (after.references().contains(reference)) {
                throw failure(Failure.READBACK_FAILED);
            }
            return true;
        }
    }

    /**
     * Resolves a credential only for the duration of one synchronous callback.
     *
     * <p>The provided array is cleared before this method returns. Callers must not copy, return,
     * log, persist, bundle or retain it.
     */
    public void use(SecretRef reference, SecretUse use) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(use, "use");
        char[] plaintext = null;
        synchronized (STORE_LOCK) {
            StoreState state = inspect(storage.snapshot());
            requirePresent(state, reference);
            String encoded = storedString(state.raw(), entryKey(reference), Failure.CORRUPT_STORE);
            try {
                plaintext = storage.decrypt(encoded);
                validateSecret(plaintext, Failure.CORRUPT_STORE);
            } catch (Exception error) {
                if (plaintext != null) Arrays.fill(plaintext, '\0');
                if (error instanceof SecretStoreException storeFailure) throw storeFailure;
                throw failure(Failure.KEY_UNAVAILABLE);
            }
        }
        try {
            use.accept(plaintext);
        } catch (RuntimeException error) {
            throw failure(Failure.USE_FAILED);
        } finally {
            Arrays.fill(plaintext, '\0');
        }
    }

    /** Idempotently creates or refreshes refs for the retained Android 0.2 credential slots. */
    public LegacyRefs migrateLegacy() {
        synchronized (STORE_LOCK) {
            StoreState before = inspect(storage.snapshot());
            return writeLegacyProjection(before, legacyStoredValues(before.raw()), false);
        }
    }

    /**
     * Commits already encrypted Android 0.2 values and their ref projection in one durable write.
     * This is a narrow migration bridge; ciphertext is never a Provider or UI API.
     */
    public LegacyRefs commitLegacyPrepared(Map<LegacySlot, String> preparedValues) {
        EnumMap<LegacySlot, String> exact = exactLegacyValues(preparedValues);
        synchronized (STORE_LOCK) {
            StoreState before = inspect(storage.snapshot());
            return writeLegacyProjection(before, exact, true);
        }
    }

    /**
     * Restores encrypted legacy values with their exact pre-transaction identities.
     *
     * <p>This is restricted to the settings recovery journal. Reusing the original identities is
     * required when a failed save temporarily retired a binding; allocating replacement identities
     * would leave otherwise restored configuration pointing at stale refs.</p>
     */
    public LegacyRefs restoreLegacyPrepared(
            Map<LegacySlot, String> preparedValues,
            LegacyRefs expectedRefs) {
        EnumMap<LegacySlot, String> exact = exactLegacyValues(preparedValues);
        EnumMap<LegacySlot, SecretRef> expected = exactLegacyRefs(expectedRefs, exact);
        synchronized (STORE_LOCK) {
            StoreState before = inspect(storage.snapshot());
            Map<String, String> values = baseTargetValues();
            Set<String> removals = new HashSet<>();
            for (LegacySlot slot : LegacySlot.values()) {
                String source = exact.get(slot);
                SecretRef current = before.bindings().get(slot);
                SecretRef restored = expected.get(slot);
                if (restored != null
                        && before.references().contains(restored)
                        && !restored.equals(current)) {
                    throw failure(Failure.CORRUPT_STORE);
                }
                if (current != null && !current.equals(restored)) {
                    removals.add(entryKey(current));
                    removals.add(kindKey(current));
                }
                if (restored == null) {
                    removals.add(slot.legacyKey());
                    removals.add(bindingKey(slot));
                    continue;
                }
                values.put(slot.legacyKey(), source);
                values.put(bindingKey(slot), restored.opaqueId());
                values.put(entryKey(restored), source);
                values.put(kindKey(restored), restored.kind().name());
            }
            if (mutationNeeded(before.raw(), values, removals)) commit(values, removals);
            verifyLegacyState(exact, expected, Failure.READBACK_FAILED);
            return refsFrom(expected);
        }
    }

    /** Performs a read-only exact readback of the settings transaction's encrypted values. */
    public void verifyLegacyPrepared(
            Map<LegacySlot, String> preparedValues,
            LegacyRefs expectedRefs) {
        EnumMap<LegacySlot, String> exact = exactLegacyValues(preparedValues);
        EnumMap<LegacySlot, SecretRef> expected = exactLegacyRefs(expectedRefs, exact);
        synchronized (STORE_LOCK) {
            verifyLegacyState(exact, expected, Failure.READBACK_FAILED);
        }
    }

    /** Returns retained ciphertext only for the existing settings recovery journal. */
    public String storedLegacyValue(LegacySlot slot) {
        Objects.requireNonNull(slot, "slot");
        synchronized (STORE_LOCK) {
            StoreState state = inspect(storage.snapshot());
            Object value = state.raw().get(slot.legacyKey());
            if (value == null) return "";
            if (!(value instanceof String string)
                    || string.length() > MAX_STORED_VALUE_UTF16_UNITS) {
                throw failure(Failure.CORRUPT_SOURCE);
            }
            return string;
        }
    }

    private LegacyRefs writeLegacyProjection(
            StoreState before,
            EnumMap<LegacySlot, String> sources,
            boolean writeSources) {
        Map<String, String> values = baseTargetValues();
        Set<String> removals = new HashSet<>();
        EnumMap<LegacySlot, SecretRef> projected = new EnumMap<>(LegacySlot.class);
        Set<SecretRef> allocated = new HashSet<>(before.references());
        for (LegacySlot slot : LegacySlot.values()) {
            String source = sources.get(slot);
            if (writeSources) {
                if (source.isEmpty()) removals.add(slot.legacyKey());
                else values.put(slot.legacyKey(), source);
            }
            SecretRef existing = before.bindings().get(slot);
            if (source.isEmpty()) {
                removals.add(bindingKey(slot));
                if (existing != null) {
                    removals.add(entryKey(existing));
                    removals.add(kindKey(existing));
                }
                continue;
            }
            SecretRef reference = existing;
            if (reference == null) {
                if (allocated.size() >= MAX_ENTRIES) {
                    throw failure(Failure.STORE_LIMIT_EXCEEDED);
                }
                reference = newReference(slot.kind(), allocated);
                allocated.add(reference);
            }
            projected.put(slot, reference);
            values.put(bindingKey(slot), reference.opaqueId());
            values.put(entryKey(reference), source);
            values.put(kindKey(reference), reference.kind().name());
        }
        if (mutationNeeded(before.raw(), values, removals)) commit(values, removals);
        StoreState after = inspect(storage.snapshot());
        for (LegacySlot slot : LegacySlot.values()) {
            SecretRef expected = projected.get(slot);
            if (!Objects.equals(expected, after.bindings().get(slot))) {
                throw failure(Failure.READBACK_FAILED);
            }
            if (expected != null) {
                String expectedCipher = sources.get(slot);
                String actualCipher = storedString(
                        after.raw(), entryKey(expected), Failure.READBACK_FAILED);
                if (!expectedCipher.equals(actualCipher)) throw failure(Failure.READBACK_FAILED);
            }
        }
        return refsFrom(after.bindings());
    }

    private void verifyLegacyState(
            EnumMap<LegacySlot, String> expectedSources,
            EnumMap<LegacySlot, SecretRef> expectedRefs,
            Failure onFailure) {
        StoreState state = inspect(storage.snapshot());
        for (LegacySlot slot : LegacySlot.values()) {
            String expectedSource = expectedSources.get(slot);
            SecretRef expectedRef = expectedRefs.get(slot);
            Object actualSource = state.raw().get(slot.legacyKey());
            if (expectedSource.isEmpty()) {
                if (actualSource != null || expectedRef != null) throw failure(onFailure);
            } else if (!expectedSource.equals(actualSource) || expectedRef == null) {
                throw failure(onFailure);
            }
            if (!Objects.equals(expectedRef, state.bindings().get(slot))) {
                throw failure(onFailure);
            }
            if (expectedRef != null
                    && (!expectedSource.equals(state.raw().get(entryKey(expectedRef)))
                    || !expectedRef.kind().name().equals(state.raw().get(kindKey(expectedRef))))) {
                throw failure(onFailure);
            }
        }
    }

    private void commitAndVerify(
            Map<String, String> values,
            Set<String> removals,
            SecretRef expected,
            String expectedCiphertext) {
        commit(values, removals);
        StoreState after = inspect(storage.snapshot());
        if (!after.references().contains(expected)
                || !expected.kind().name().equals(after.raw().get(kindKey(expected)))
                || !expectedCiphertext.equals(after.raw().get(entryKey(expected)))) {
            throw failure(Failure.READBACK_FAILED);
        }
        for (String removed : removals) {
            if (after.raw().containsKey(removed)) throw failure(Failure.READBACK_FAILED);
        }
    }

    private void commit(Map<String, String> values, Set<String> removals) {
        try {
            storage.commit(values, removals);
        } catch (RuntimeException error) {
            throw failure(Failure.COMMIT_FAILED);
        }
    }

    private StoreState inspect(Map<String, ?> raw) {
        if (raw.size() > (MAX_ENTRIES * 2) + 32) throw failure(Failure.STORE_LIMIT_EXCEEDED);
        boolean hasTarget = raw.keySet().stream().anyMatch(key -> key.startsWith(TARGET_PREFIX));
        if (!hasTarget) return new StoreState(raw, Set.of(), new EnumMap<>(LegacySlot.class));
        int format = requiredInteger(raw, FORMAT_KEY);
        int migration = requiredInteger(raw, MIGRATION_KEY);
        if (format != FORMAT_VERSION || migration != MIGRATION_VERSION) {
            throw failure(Failure.UNKNOWN_VERSION);
        }
        Object backup = raw.get(BACKUP_KEY);
        if (!(backup instanceof String backupValue) || !"true".equals(backupValue)) {
            throw failure(Failure.PARTIAL_STORE);
        }
        Map<String, SecretRef.Kind> kinds = new HashMap<>();
        Map<String, String> entries = new HashMap<>();
        EnumMap<LegacySlot, SecretRef> bindings = new EnumMap<>(LegacySlot.class);
        for (Map.Entry<String, ?> item : raw.entrySet()) {
            String key = item.getKey();
            if (!key.startsWith(TARGET_PREFIX)
                    || key.equals(FORMAT_KEY)
                    || key.equals(MIGRATION_KEY)
                    || key.equals(BACKUP_KEY)) {
                continue;
            }
            Object value = item.getValue();
            if (!(value instanceof String string)
                    || string.length() > MAX_STORED_VALUE_UTF16_UNITS) {
                throw failure(Failure.CORRUPT_STORE);
            }
            if (key.startsWith(ENTRY_PREFIX)) {
                entries.put(key.substring(ENTRY_PREFIX.length()), string);
            } else if (key.startsWith(KIND_PREFIX)) {
                try {
                    kinds.put(key.substring(KIND_PREFIX.length()), SecretRef.Kind.valueOf(string));
                } catch (IllegalArgumentException error) {
                    throw failure(Failure.CORRUPT_STORE);
                }
            } else if (key.startsWith(BINDING_PREFIX)) {
                LegacySlot slot = LegacySlot.fromStorageName(
                        key.substring(BINDING_PREFIX.length()));
                if (slot == null) throw failure(Failure.CORRUPT_STORE);
                SecretRef reference = parseReference(slot.kind(), string, Failure.CORRUPT_STORE);
                bindings.put(slot, reference);
            } else {
                throw failure(Failure.CORRUPT_STORE);
            }
        }
        if (!entries.keySet().equals(kinds.keySet()) || entries.size() > MAX_ENTRIES) {
            throw failure(Failure.PARTIAL_STORE);
        }
        Set<SecretRef> references = new HashSet<>();
        for (Map.Entry<String, SecretRef.Kind> item : kinds.entrySet()) {
            SecretRef reference = parseReference(item.getValue(), item.getKey(), Failure.CORRUPT_STORE);
            String encoded = entries.get(item.getKey());
            if (encoded == null || encoded.isBlank()) throw failure(Failure.CORRUPT_STORE);
            references.add(reference);
        }
        for (Map.Entry<LegacySlot, SecretRef> item : bindings.entrySet()) {
            if (!references.contains(item.getValue())) throw failure(Failure.PARTIAL_STORE);
        }
        if (new HashSet<>(bindings.values()).size() != bindings.size()) {
            throw failure(Failure.CORRUPT_STORE);
        }
        return new StoreState(raw, Set.copyOf(references), bindings);
    }

    private SecretRef newReference(SecretRef.Kind kind, Set<SecretRef> existing) {
        for (int attempt = 0; attempt < MAX_ID_ATTEMPTS; attempt++) {
            String candidate;
            try {
                candidate = idSource.nextId();
            } catch (RuntimeException error) {
                throw failure(Failure.ID_GENERATION_FAILED);
            }
            SecretRef reference = parseReference(kind, candidate, Failure.ID_GENERATION_FAILED);
            if (!existing.contains(reference)) return reference;
        }
        throw failure(Failure.ID_GENERATION_FAILED);
    }

    private String protect(char[] secret) {
        try {
            String prepared = storage.protect(secret);
            if (prepared.isBlank() || prepared.length() > MAX_STORED_VALUE_UTF16_UNITS) {
                throw failure(Failure.KEY_UNAVAILABLE);
            }
            return prepared;
        } catch (RuntimeException error) {
            if (error instanceof SecretStoreException secretFailure) throw secretFailure;
            throw failure(Failure.KEY_UNAVAILABLE);
        }
    }

    private static char[] validatedSecretCopy(char[] secret) {
        if (secret == null) throw failure(Failure.INVALID_INPUT);
        char[] copy = secret.clone();
        try {
            validateSecret(copy, Failure.INVALID_INPUT);
            return copy;
        } catch (RuntimeException error) {
            Arrays.fill(copy, '\0');
            throw error;
        }
    }

    private static void validateSecret(char[] secret, Failure onFailure) {
        if (secret == null || secret.length == 0) throw failure(onFailure);
        boolean nonWhitespace = false;
        int codePoints = 0;
        for (int index = 0; index < secret.length; ) {
            char current = secret[index];
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= secret.length || !Character.isLowSurrogate(secret[index + 1])) {
                    throw failure(onFailure);
                }
                index += 2;
            } else if (Character.isLowSurrogate(current)) {
                throw failure(onFailure);
            } else {
                index++;
            }
            codePoints++;
            if (!Character.isWhitespace(current)) nonWhitespace = true;
            if (codePoints > MAX_SECRET_CODE_POINTS) {
                throw failure(onFailure);
            }
        }
        if (!nonWhitespace) throw failure(onFailure);
    }

    private static Map<String, String> baseTargetValues() {
        return new HashMap<>(Map.of(
                FORMAT_KEY, Integer.toString(FORMAT_VERSION),
                MIGRATION_KEY, Integer.toString(MIGRATION_VERSION),
                BACKUP_KEY, "true"));
    }

    private static EnumMap<LegacySlot, String> legacyStoredValues(Map<String, ?> raw) {
        EnumMap<LegacySlot, String> values = new EnumMap<>(LegacySlot.class);
        for (LegacySlot slot : LegacySlot.values()) {
            Object value = raw.get(slot.legacyKey());
            if (value == null) values.put(slot, "");
            else if (value instanceof String string
                    && string.length() <= MAX_STORED_VALUE_UTF16_UNITS) {
                values.put(slot, string);
            } else {
                throw failure(Failure.CORRUPT_SOURCE);
            }
        }
        return values;
    }

    private static EnumMap<LegacySlot, String> exactLegacyValues(
            Map<LegacySlot, String> preparedValues) {
        if (preparedValues == null || preparedValues.size() != LegacySlot.values().length) {
            throw failure(Failure.INVALID_INPUT);
        }
        EnumMap<LegacySlot, String> exact = new EnumMap<>(LegacySlot.class);
        for (LegacySlot slot : LegacySlot.values()) {
            String value = preparedValues.get(slot);
            if (value == null || value.length() > MAX_STORED_VALUE_UTF16_UNITS) {
                throw failure(Failure.INVALID_INPUT);
            }
            exact.put(slot, value);
        }
        return exact;
    }

    private static EnumMap<LegacySlot, SecretRef> exactLegacyRefs(
            LegacyRefs references,
            EnumMap<LegacySlot, String> sources) {
        LegacyRefs safe = Objects.requireNonNull(references, "expectedRefs");
        EnumMap<LegacySlot, SecretRef> exact = new EnumMap<>(LegacySlot.class);
        putExpectedRef(exact, LegacySlot.STT_ASR, safe.sttAsr(), sources);
        putExpectedRef(exact, LegacySlot.STREAMING_ASR, safe.streamingAsr(), sources);
        putExpectedRef(exact, LegacySlot.LLM, safe.llm(), sources);
        return exact;
    }

    private static void putExpectedRef(
            EnumMap<LegacySlot, SecretRef> target,
            LegacySlot slot,
            Optional<SecretRef> reference,
            EnumMap<LegacySlot, String> sources) {
        Optional<SecretRef> safe = Objects.requireNonNull(reference, "legacy ref");
        boolean sourcePresent = !sources.get(slot).isEmpty();
        if (safe.isPresent() != sourcePresent) throw failure(Failure.INVALID_INPUT);
        if (safe.isEmpty()) return;
        SecretRef exact = safe.orElseThrow();
        if (exact.kind() != slot.kind()) throw failure(Failure.INVALID_INPUT);
        target.put(slot, exact);
    }

    private static boolean mutationNeeded(
            Map<String, ?> before,
            Map<String, String> values,
            Set<String> removals) {
        for (Map.Entry<String, String> item : values.entrySet()) {
            if (!item.getValue().equals(before.get(item.getKey()))) return true;
        }
        for (String key : removals) if (before.containsKey(key)) return true;
        return false;
    }

    private static int requiredInteger(Map<String, ?> raw, String key) {
        Object value = raw.get(key);
        if (!(value instanceof String string)) throw failure(Failure.PARTIAL_STORE);
        try {
            return Integer.parseInt(string);
        } catch (NumberFormatException error) {
            throw failure(Failure.CORRUPT_STORE);
        }
    }

    private static SecretRef parseReference(
            SecretRef.Kind kind,
            String opaqueId,
            Failure onFailure) {
        try {
            return new SecretRef(kind, opaqueId);
        } catch (RuntimeException error) {
            throw failure(onFailure);
        }
    }

    private static void requirePresent(StoreState state, SecretRef reference) {
        if (!state.references().contains(reference)) throw failure(Failure.SECRET_NOT_FOUND);
    }

    private static String storedString(Map<String, ?> raw, String key, Failure onFailure) {
        Object value = raw.get(key);
        if (!(value instanceof String string)
                || string.isBlank()
                || string.length() > MAX_STORED_VALUE_UTF16_UNITS) {
            throw failure(onFailure);
        }
        return string;
    }

    private static String entryKey(SecretRef reference) {
        return ENTRY_PREFIX + reference.opaqueId();
    }

    private static String kindKey(SecretRef reference) {
        return KIND_PREFIX + reference.opaqueId();
    }

    private static String bindingKey(LegacySlot slot) {
        return BINDING_PREFIX + slot.storageName();
    }

    private static LegacyRefs refsFrom(EnumMap<LegacySlot, SecretRef> bindings) {
        return new LegacyRefs(
                Optional.ofNullable(bindings.get(LegacySlot.STT_ASR)),
                Optional.ofNullable(bindings.get(LegacySlot.STREAMING_ASR)),
                Optional.ofNullable(bindings.get(LegacySlot.LLM)));
    }

    private static SecretStoreException failure(Failure failure) {
        return new SecretStoreException(failure);
    }

    @FunctionalInterface
    public interface SecretUse {
        void accept(char[] secret);
    }

    @FunctionalInterface
    interface IdSource {
        String nextId();
    }

    interface Storage {
        Map<String, ?> snapshot();

        String protect(char[] plaintext);

        char[] decrypt(String encoded) throws Exception;

        void commit(Map<String, String> values, Set<String> removals);
    }

    private static final class SecureStorage implements Storage {
        private final SecurePreferences preferences;

        private SecureStorage(SecurePreferences preferences) {
            this.preferences = Objects.requireNonNull(preferences, "preferences");
        }

        @Override
        public Map<String, ?> snapshot() {
            return preferences.snapshot();
        }

        @Override
        public String protect(char[] plaintext) {
            return preferences.prepareStored(plaintext);
        }

        @Override
        public char[] decrypt(String encoded) throws Exception {
            return preferences.decryptStored(encoded);
        }

        @Override
        public void commit(Map<String, String> values, Set<String> removals) {
            preferences.commitStored(values, removals);
        }
    }

    public enum LegacySlot {
        STT_ASR(SecretRef.Kind.ASR, "stt_api_key", "stt_asr"),
        STREAMING_ASR(SecretRef.Kind.ASR, "streaming_api_key", "streaming_asr"),
        LLM(SecretRef.Kind.LLM, "llm_api_key", "llm");

        private final SecretRef.Kind kind;
        private final String legacyKey;
        private final String storageName;

        LegacySlot(SecretRef.Kind kind, String legacyKey, String storageName) {
            this.kind = kind;
            this.legacyKey = legacyKey;
            this.storageName = storageName;
        }

        public SecretRef.Kind kind() {
            return kind;
        }

        String legacyKey() {
            return legacyKey;
        }

        String storageName() {
            return storageName;
        }

        static LegacySlot fromStorageName(String value) {
            for (LegacySlot slot : values()) if (slot.storageName.equals(value)) return slot;
            return null;
        }
    }

    public record LegacyRefs(
            Optional<SecretRef> sttAsr,
            Optional<SecretRef> streamingAsr,
            Optional<SecretRef> llm) {
        public LegacyRefs {
            sttAsr = Objects.requireNonNull(sttAsr, "sttAsr");
            streamingAsr = Objects.requireNonNull(streamingAsr, "streamingAsr");
            llm = Objects.requireNonNull(llm, "llm");
        }

        @Override
        public String toString() {
            return "LegacyRefs{present=<redacted>}";
        }
    }

    public enum Failure {
        INVALID_INPUT,
        CORRUPT_SOURCE,
        UNKNOWN_VERSION,
        PARTIAL_STORE,
        CORRUPT_STORE,
        STORE_LIMIT_EXCEEDED,
        ID_GENERATION_FAILED,
        KEY_UNAVAILABLE,
        COMMIT_FAILED,
        READBACK_FAILED,
        SECRET_NOT_FOUND,
        LEGACY_AUTHORITY,
        USE_FAILED
    }

    public static final class SecretStoreException extends IllegalStateException {
        private final Failure failure;

        private SecretStoreException(Failure failure) {
            super("Secret store operation failed");
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        public Failure failure() {
            return failure;
        }

        @Override
        public String toString() {
            return "SecretStoreException{failure=" + failure + "}";
        }
    }

    private record StoreState(
            Map<String, ?> raw,
            Set<SecretRef> references,
            EnumMap<LegacySlot, SecretRef> bindings) {
        private StoreState {
            raw = Map.copyOf(raw);
            references = Set.copyOf(references);
            bindings = new EnumMap<>(bindings);
        }

        @Override
        public String toString() {
            return "StoreState{contents=<redacted>}";
        }
    }
}
