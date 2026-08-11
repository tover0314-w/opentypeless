package com.opentypeless.android.offline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Reads an untrusted stream without allowing it to exceed the caller's memory budget. */
final class BoundedInputReader {
    private BoundedInputReader() {}

    static byte[] read(InputStream input, int minimumBytes, int maximumBytes) throws IOException {
        if (input == null) throw new IllegalArgumentException("Input stream is required");
        if (minimumBytes < 0 || maximumBytes < minimumBytes) {
            throw new IllegalArgumentException("Invalid stream size limits");
        }
        int initialCapacity = Math.min(maximumBytes, 256 * 1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialCapacity);
        byte[] buffer = new byte[64 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            if (read > maximumBytes - total) {
                throw new IllegalStateException("Offline audio exceeded the size limit");
            }
            total += read;
            output.write(buffer, 0, read);
        }
        if (total < minimumBytes) {
            throw new IllegalStateException("Offline audio was incomplete");
        }
        return output.toByteArray();
    }
}
