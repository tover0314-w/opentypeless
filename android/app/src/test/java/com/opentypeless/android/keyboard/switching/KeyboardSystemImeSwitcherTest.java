package com.opentypeless.android.keyboard.switching;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class KeyboardSystemImeSwitcherTest {
    @Test
    public void successfulNextImeDoesNotOpenPicker() {
        FakePlatform platform = new FakePlatform(true, true, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.NEXT_INPUT_METHOD_REQUESTED,
                KeyboardSystemImeSwitcher.requestNext(platform));
        assertEquals(1, platform.nextCalls.get());
        assertEquals(0, platform.pickerCalls.get());
    }

    @Test
    public void noNextImeFallsBackToPicker() {
        FakePlatform platform = new FakePlatform(false, true, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_NO_NEXT,
                KeyboardSystemImeSwitcher.requestNext(platform));
        assertEquals(1, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void platformFailureUsesPickerWithoutLeakingExceptionText() {
        FakePlatform platform = new FakePlatform(false, true, true, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_AFTER_PLATFORM_FAILURE,
                KeyboardSystemImeSwitcher.requestNext(platform));
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void pickerFailureIsStableAndDoesNotRetry() {
        FakePlatform platform = new FakePlatform(false, false, true, true);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.FAILED,
                KeyboardSystemImeSwitcher.requestNext(platform));
        assertEquals(1, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    @Test
    public void explicitPickerNeverAttemptsNextIme() {
        FakePlatform platform = new FakePlatform(true, true, false, false);

        assertEquals(
                KeyboardSystemImeSwitcher.Outcome.PICKER_SHOWN_NO_NEXT,
                KeyboardSystemImeSwitcher.requestPicker(platform));
        assertEquals(0, platform.nextCalls.get());
        assertEquals(1, platform.pickerCalls.get());
    }

    private static final class FakePlatform implements KeyboardSystemImeSwitcher.Platform {
        final AtomicInteger nextCalls = new AtomicInteger();
        final AtomicInteger pickerCalls = new AtomicInteger();
        private final boolean nextResult;
        private final boolean pickerResult;
        private final boolean throwNext;
        private final boolean throwPicker;

        FakePlatform(
                boolean nextResult,
                boolean pickerResult,
                boolean throwNext,
                boolean throwPicker) {
            this.nextResult = nextResult;
            this.pickerResult = pickerResult;
            this.throwNext = throwNext;
            this.throwPicker = throwPicker;
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
