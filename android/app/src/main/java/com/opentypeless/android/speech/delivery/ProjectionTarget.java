package com.opentypeless.android.speech.delivery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;

/**
 * Target binding without retaining plaintext context. Connection identity uses reference equality;
 * epoch/package/field/selection and context hashes protect reused connections and repeated text.
 */
public final class ProjectionTarget {
    private final Object connectionIdentity;
    private final long editorEpoch;
    private final String packageName;
    private final int fieldId;
    private final int initialSelection;
    private final int precedingUtf16Length;
    private final int followingUtf16Length;
    private final byte[] precedingHash;
    private final byte[] followingHash;

    private ProjectionTarget(
            Object connectionIdentity,
            ProjectionContext context,
            String preceding,
            String following) {
        this.connectionIdentity = connectionIdentity;
        editorEpoch = context.editorEpoch();
        packageName = context.packageName();
        fieldId = context.fieldId();
        initialSelection = context.selectionStart();
        precedingUtf16Length = preceding.length();
        followingUtf16Length = following.length();
        precedingHash = hash(preceding);
        followingHash = hash(following);
    }

    public static ProjectionTarget capture(ProjectionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ProjectionContext context = snapshot.context();
        if (context.sensitive()) {
            throw new IllegalArgumentException("sensitive editors cannot become voice targets");
        }
        if (!context.selectionKnown()) {
            throw new IllegalArgumentException("unknown selection is fail-closed");
        }
        if (context.hasSelection()) {
            throw new IllegalArgumentException(
                    "selected text requires the separate command projection");
        }
        String preceding = lastCodePoints(
                snapshot.textBeforeCursor(), EditorProjectionLimits.CONTEXT_CODE_POINTS);
        String following = firstCodePoints(
                snapshot.textAfterCursor(), EditorProjectionLimits.CONTEXT_CODE_POINTS);
        return new ProjectionTarget(
                snapshot.connectionIdentity(), context, preceding, following);
    }

    public TargetValidation validate(ProjectionSnapshot snapshot, String insertedText) {
        Objects.requireNonNull(snapshot, "snapshot");
        String inserted = Objects.requireNonNullElse(insertedText, "");
        ProjectionContext context = snapshot.context();
        if (snapshot.connectionIdentity() != connectionIdentity) {
            return TargetValidation.invalid("InputConnection identity changed");
        }
        if (context.editorEpoch() != editorEpoch
                || !context.packageName().equals(packageName)
                || context.fieldId() != fieldId) {
            return TargetValidation.invalid("editor epoch, package, or field changed");
        }
        if (context.sensitive()) return TargetValidation.invalid("editor became sensitive");
        if (!context.selectionKnown() || context.hasSelection()) {
            return TargetValidation.invalid("selection is unknown or non-collapsed");
        }
        long expectedSelection = (long) initialSelection + inserted.length();
        if (expectedSelection > Integer.MAX_VALUE
                || context.selectionStart() != (int) expectedSelection) {
            return TargetValidation.invalid("cursor left the owned voice projection");
        }
        String before = snapshot.textBeforeCursor();
        if (!before.endsWith(inserted)) {
            return TargetValidation.invalid("owned voice suffix is not present");
        }
        int baseEnd = before.length() - inserted.length();
        if (baseEnd < precedingUtf16Length) {
            return TargetValidation.invalid("preceding context is incomplete");
        }
        String preceding = before.substring(baseEnd - precedingUtf16Length, baseEnd);
        String after = snapshot.textAfterCursor();
        if (after.length() < followingUtf16Length) {
            return TargetValidation.invalid("following context is incomplete");
        }
        String following = after.substring(0, followingUtf16Length);
        if (!MessageDigest.isEqual(precedingHash, hash(preceding))
                || !MessageDigest.isEqual(followingHash, hash(following))) {
            return TargetValidation.invalid("surrounding context fingerprint changed");
        }
        return TargetValidation.accepted();
    }

    public int requiredBeforeUtf16(String insertedText) {
        long requested = (long) precedingUtf16Length
                + Objects.requireNonNullElse(insertedText, "").length();
        return (int) Math.min(EditorProjectionLimits.MAX_SNAPSHOT_UTF16, requested);
    }

    public int requiredAfterUtf16() {
        return followingUtf16Length;
    }

    public int initialSelection() {
        return initialSelection;
    }

    private static byte[] hash(String value) {
        try {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            ByteBuffer material = ByteBuffer.allocate(Integer.BYTES + encoded.length);
            material.putInt(encoded.length).put(encoded);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.array());
            Arrays.fill(encoded, (byte) 0);
            Arrays.fill(material.array(), (byte) 0);
            return digest;
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String lastCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        int start = value.offsetByCodePoints(0, Math.max(0, count - maximum));
        return value.substring(start);
    }

    private static String firstCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        int end = value.offsetByCodePoints(0, Math.min(count, maximum));
        return value.substring(0, end);
    }

    public record TargetValidation(boolean valid, String reason) {
        public TargetValidation {
            reason = Objects.requireNonNullElse(reason, "");
        }

        static TargetValidation accepted() {
            return new TargetValidation(true, "");
        }

        static TargetValidation invalid(String reason) {
            return new TargetValidation(false, reason);
        }
    }
}
