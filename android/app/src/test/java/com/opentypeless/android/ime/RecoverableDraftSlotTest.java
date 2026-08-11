package com.opentypeless.android.ime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RecoverableDraftSlotTest {
    @Test
    public void laterFinalFromTheSameSessionSupersedesItsPartial() {
        RecoverableDraftSlot slot = new RecoverableDraftSlot();
        Object source = new Object();

        assertTrue(slot.save("你好", source));
        assertTrue(slot.save("你好，世界。", source));

        RecoverableDraftSlot.Draft draft = slot.get();
        assertEquals("你好，世界。", draft.text());
    }

    @Test
    public void aDifferentSessionCannotOverwriteAnUnresolvedDraft() {
        RecoverableDraftSlot slot = new RecoverableDraftSlot();
        Object first = new Object();
        Object second = new Object();

        assertTrue(slot.save("first", first));
        assertFalse(slot.save("second", second));
        assertEquals("first", slot.get().text());
    }

    @Test
    public void persistedRestoreCannotOverwriteAResidentDraft() {
        RecoverableDraftSlot slot = new RecoverableDraftSlot();

        assertTrue(slot.restore("persisted"));
        assertFalse(slot.restore("newer"));
        assertEquals("persisted", slot.get().text());
        slot.clear();
        assertFalse(slot.hasDraft());
    }
}
