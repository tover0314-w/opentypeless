package com.opentypeless.android.rime.userdata;

/** Stable, content-free failure for the local Rime UserDB lifecycle. */
public final class RimeUserDataException extends Exception {
    public enum Code {
        BUSY,
        STORAGE_FAILED,
        LIMIT_EXCEEDED,
        NO_CHECKPOINT
    }

    private final Code code;

    public RimeUserDataException(Code code) {
        super(code.name());
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    RimeUserDataException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = java.util.Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
