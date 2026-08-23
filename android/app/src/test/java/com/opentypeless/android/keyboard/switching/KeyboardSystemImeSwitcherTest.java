package com.opentypeless.android.keyboard.switching;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class KeyboardSystemImeSwitcherTest {
    @Test
    public void successfulSingleAlternativeDoesNotRequestHistoryNextOrPicker() {
        FakePlatform platform = new FakePlatform(
                true, true, true, true, false, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.SINGLE_ALTERNATIVE_REQUESTED,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(0, platform.previousCalls.get());
        assertEquals(0, platform.nextCalls.get());
        assertEquals(0, platform.pickerCalls.get());
    }

    @Test
    public void noSingleAlternativeUsesPreviousWithoutRequestingNextOrPicker() {
        FakePlatform platform = new FakePlatform(
                false, true, true, true, false, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PREVIOUS_INPUT_METHOD_REQUESTED,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(0, platform.nextCalls.get());
        assertEquals(0, platform.pickerCalls.get());
    }

    @Test
    public void noPreviousImeUsesNextWithoutOpeningPicker() {
        FakePlatform platform = new FakePlatform(
                false, false, true, true, false, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.NEXT_INPUT_METHOD_REQUESTED,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(1, platform.nextCalls.get());
        assertEquals(0, platform.pickerCalls.get());
    }

    @Test
    public void noNextImeFallsBackToPicker() {
        FakePlatform platform = new FakePlatform(
                false, false, false, true, false, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_NO_NEXT,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(1, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void directSingleFailureStillUsesSuccessfulPreviousIme() {
        FakePlatform platform = new FakePlatform(
                false, true, true, true, true, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PREVIOUS_INPUT_METHOD_REQUESTED,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(0, platform.nextCalls.get());
        assertEquals(0, platform.pickerCalls.get());
    }

    @Test
    public void directPlatformFailuresUsePickerWithoutLeakingExceptionText() {
        FakePlatform platform = new FakePlatform(
                false, false, false, true, true, true, true, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_AFTER_PLATFORM_FAILURE,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(1, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void pickerFailureIsStableAndDoesNotRetry() {
        FakePlatform platform = new FakePlatform(
                false, false, false, false, true, true, true, true);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.FAILED,
                KeyboardSystemImeSwitcher.requestAlternative(platform));
        assertEquals(1, platform.singleCalls.get());
        assertEquals(1, platform.previousCalls.get());
        assertEquals(1, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void explicitPickerNeverAttemptsNextIme() {
        FakePlatform platform = new FakePlatform(
                true, true, true, true, false, false, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_NO_NEXT,
                KeyboardSystemImeSwitcher.requestPicker(platform));
        assertEquals(0, platform.singleCalls.get());
        assertEquals(0, platform.previousCalls.get());
        assertEquals(0, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    private static final class FakePlatform implements KeyboardSystemImeSwitcher.Platform {
        final AtomicInteger singleCalls = new AtomicInteger();
        final AtomicInteger previousCalls = new AtomicInteger();
        final AtomicInteger nextCalls = new AtomicInteger();
        final AtomicInteger pickerCalls = new AtomicInteger();
        private final boolean singleResult;
        private final boolean previousResult;
        private final boolean nextResult;
        private final boolean pickerResult;
        private final boolean throwSingle;
        private final boolean throwPrevious;
        private final boolean throwNext;
        private final boolean throwPicker;

        FakePlatform(
                boolean singleResult,
                boolean previousResult,
                boolean nextResult,
                boolean pickerResult,
                boolean throwSingle,
                boolean throwPrevious,
                boolean throwNext,
                boolean throwPicker) {
            this.singleResult = singleResult;
            this.previousResult = previousResult;
            this.nextResult = nextResult;
            this.pickerResult = pickerResult;
            this.throwSingle = throwSingle;
            this.throwPrevious = throwPrevious;
            this.throwNext = throwNext;
            this.throwPicker = throwPicker;
        }

        @Override
        public boolean switchToSingleEnabledAlternative() {
            singleCalls.incrementAndGet();
            if (throwSingle) throw new IllegalStateException("private single failure");
            return singleResult;
        }

        @Override
        public boolean switchToPreviousInputMethod() {
            previousCalls.incrementAndGet();
            if (throwPrevious) throw new IllegalStateException("private previous failure");
            return previousResult;
        }

        @Override
        public boolean switchToNextInputMethod() {
            nextCalls.incrementAndGet();
            if (throwNext) throw new IllegalStateException("private platform failure");
            return nextResult;
        }

        @Override
        public boolean showInputMethodPicker() {
            pickerCalls.incrementAndGet();
            if (throwPicker) throw new IllegalStateException("private picker failure");
            return pickerResult;
        }
    }
}
