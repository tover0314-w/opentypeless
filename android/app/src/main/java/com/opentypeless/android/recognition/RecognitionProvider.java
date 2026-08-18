package com.opentypeless.android.recognition;

import com.opentypeless.android.config.RecognitionRoute;
import com.opentypeless.android.speech.core.SessionId;

import java.util.Objects;

/** Package-confined runtime contract shared by reviewed recognition adapters. */
interface RecognitionProvider<R> extends AutoCloseable {
    ProviderDescriptor descriptor();

    ProviderRegistry.ProbeObservation probe();

    PreparationResult prepare(R request);

    Session start(R request, EventSink sink);

    @Override
    void close();

    @FunctionalInterface
    interface EventSink {
        void onEvent(RecognitionEvent event);
    }

    interface Session extends AutoCloseable {
        SessionId sessionId();

        void stop();

        void cancel();

        @Override
        void close();
    }

    sealed interface PreparationResult permits Prepared, NotPrepared {}

    record Prepared(ProviderDescriptor descriptor) implements PreparationResult {
        public Prepared {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
        }

        @Override
        public String toString() {
            return "Prepared{descriptor=<redacted>}";
        }
    }

    record NotPrepared(RecognitionRoute.FailureClass failureClass)
            implements PreparationResult {
        public NotPrepared {
            failureClass = Objects.requireNonNull(failureClass, "failureClass");
        }
    }
}
