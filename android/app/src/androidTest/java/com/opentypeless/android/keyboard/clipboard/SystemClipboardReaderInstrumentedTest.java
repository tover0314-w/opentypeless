package com.opentypeless.android.keyboard.clipboard;

import static org.junit.Assert.assertEquals;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SystemClipboardReaderInstrumentedTest {
    @Test
    public void snapshotReadsOnlyAlreadyMaterializedPrimaryText() {
        ClipData clip = ClipData.newPlainText("ignored label", "current text");

        ClipboardPanelSnapshot snapshot = SystemClipboardReader.snapshotOf(clip);

        assertEquals(ClipboardPanelSnapshot.State.TEXT, snapshot.state());
        assertEquals("current text", snapshot.text());
    }

    @Test
    public void uriAndIntentItemsAreNotCoercedOrResolved() {
        ClipDescription description = new ClipDescription(
                "ignored label", new String[] {"text/uri-list"});
        ClipData uriClip = new ClipData(
                description, new ClipData.Item(Uri.parse("content://private/item")));
        ClipData intentClip = new ClipData(
                description, new ClipData.Item(new Intent("private.action")));

        assertEquals(
                ClipboardPanelSnapshot.State.UNSUPPORTED,
                SystemClipboardReader.snapshotOf(uriClip).state());
        assertEquals(
                ClipboardPanelSnapshot.State.UNSUPPORTED,
                SystemClipboardReader.snapshotOf(intentClip).state());
    }
}
