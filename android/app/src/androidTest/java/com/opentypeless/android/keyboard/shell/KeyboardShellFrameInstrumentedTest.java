package com.opentypeless.android.keyboard.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class KeyboardShellFrameInstrumentedTest {
    @Test
    public void routeAOwnsOnlyTaggedViewSlotsAndNoEditorCapability() {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardShellFrame frame = KeyboardShellFrame.routeA(context);
        View composition = new View(context);
        View keys = new View(context);
        View extensions = new View(context);

        frame.attachToolbar(matchWrap());
        frame.attachComposition(composition, matchWrap());
        frame.attachKeys(keys, matchWrap());
        frame.attachExtensions(extensions, matchWrap());

        assertEquals(KeyboardShellRoute.ROUTE_A, frame.route());
        assertEquals(KeyboardShellFrame.ROUTE_A_ROOT_TAG, frame.root().getTag());
        assertEquals(KeyboardShellFrame.ROUTE_A_TOOLBAR_TAG, frame.toolbar().getTag());
        assertEquals(KeyboardShellFrame.ROUTE_A_COMPOSITION_TAG, composition.getTag());
        assertEquals(KeyboardShellFrame.ROUTE_A_KEYS_TAG, keys.getTag());
        assertEquals(KeyboardShellFrame.ROUTE_A_EXTENSIONS_TAG, extensions.getTag());
        assertEquals(4, frame.root().getChildCount());
    }

    @Test
    public void legacyRollbackRetainsTheOldUntaggedSlots() {
        Context context = ApplicationProvider.getApplicationContext();
        KeyboardShellFrame frame = KeyboardShellFrame.legacyVoice(context);
        View child = new View(context);

        frame.attachToolbar(matchWrap());
        frame.attachKeys(child, matchWrap());

        assertEquals(KeyboardShellRoute.LEGACY_VOICE, frame.route());
        assertEquals(KeyboardShellFrame.LEGACY_ROOT_TAG, frame.root().getTag());
        assertNull(frame.toolbar().getTag());
        assertNull(child.getTag());
        assertEquals(2, frame.root().getChildCount());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
