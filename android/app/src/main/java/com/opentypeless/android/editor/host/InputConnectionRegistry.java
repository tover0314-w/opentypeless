package com.opentypeless.android.editor.host;

import android.view.inputmethod.InputConnection;

/** Read-only resolution capability used by the future editor transaction host. */
interface InputConnectionRegistry {
    long NO_CONNECTION_TOKEN = 0L;

    /** Returns the current opaque process-local token, or {@link #NO_CONNECTION_TOKEN}. */
    long currentToken();

    /** Resolves only the exact current positive token; stale or invalid tokens return null. */
    InputConnection resolve(long token);
}
