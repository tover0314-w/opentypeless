package com.opentypeless.android.rime.importer;

/** Content-free, stable failure returned by the local Rime resource importer. */
public final class RimeImportException extends Exception {
    public enum Code {
        BUSY,
        SOURCE_UNREADABLE,
        ARCHIVE_INVALID,
        ARCHIVE_LIMIT,
        PATH_INVALID,
        MANIFEST_INVALID,
        FILE_SET_MISMATCH,
        HASH_MISMATCH,
        RESOURCE_UNSAFE,
        RUNTIME_INCOMPATIBLE,
        DEPLOY_FAILED,
        STORAGE_FAILED
    }

    private final Code code;

    public RimeImportException(Code code) {
        super(code.name());
        this.code = code;
    }

    public RimeImportException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    @Override
    public String toString() {
        return "RimeImportException{code=" + code + '}';
    }
}
