package com.opentypeless.android.speech.delivery;

/** IME lifecycle bridge; the service updates epoch/field/selection outside InputConnection. */
@FunctionalInterface
public interface ProjectionMetadataProvider {
    ProjectionContext current();
}
