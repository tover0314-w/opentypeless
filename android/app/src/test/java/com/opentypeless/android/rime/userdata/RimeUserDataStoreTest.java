package com.opentypeless.android.rime.userdata;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class RimeUserDataStoreTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void checkpointCopiesOnlyUserDbAndRestoreIsAtomic() throws Exception {
        File root = temporary.newFolder("userdata");
        RimeUserDataStore store = new RimeUserDataStore(root);
        byte[] original = "synthetic-user-frequency".getBytes(StandardCharsets.UTF_8);
        try (RimeUserDataStore.Session session = store.openSession()) {
            File database = new File(session.userDirectory(), "local.userdb");
            assertTrue(database.mkdirs());
            Files.write(new File(database, "000001.log").toPath(), original);
            File build = new File(session.userDirectory(), "build");
            assertTrue(build.mkdirs());
            Files.write(new File(build, "local.table.bin").toPath(), new byte[] {9});
            session.checkpoint();
        }

        RimeUserDataStore.Status status = store.status();
        assertTrue(status.hasUserData());
        assertTrue(status.hasCheckpoint());
        assertEquals(1, status.fileCount());
        Files.write(new File(root, "current/local.userdb/000001.log").toPath(), new byte[] {1});
        store.restoreLatestCheckpoint();

        assertArrayEquals(original, Files.readAllBytes(
                new File(root, "current/local.userdb/000001.log").toPath()));
        assertFalse(new File(root, "current/build").exists());
        store.clear();
        assertFalse(store.status().hasUserData());
        assertFalse(store.status().hasCheckpoint());
        assertTrue(new File(root, "current").isDirectory());
    }

    @Test
    public void interruptedCheckpointRecoversLastCompleteCopy() throws Exception {
        File root = temporary.newFolder("recovery");
        File old = new File(root, ".checkpoint-old/local.userdb");
        assertTrue(old.mkdirs());
        Files.write(new File(old, "CURRENT").toPath(), new byte[] {7});
        File partial = new File(root, ".checkpoint-new/local.userdb");
        assertTrue(partial.mkdirs());
        Files.write(new File(partial, "partial").toPath(), new byte[] {8});

        RimeUserDataStore store = new RimeUserDataStore(root);
        try (RimeUserDataStore.Session ignored = store.openSession()) {
            assertTrue(new File(root, "checkpoint/local.userdb/CURRENT").isFile());
            assertFalse(new File(root, ".checkpoint-new").exists());
            assertFalse(new File(root, ".checkpoint-old").exists());
        }
    }

    @Test
    public void symlinkAndBusyManagementFailClosed() throws Exception {
        File root = temporary.newFolder("bounded");
        RimeUserDataStore store = new RimeUserDataStore(root);
        try (RimeUserDataStore.Session session = store.openSession()) {
            try {
                store.clear();
                throw new AssertionError("clear must reject an active native lease");
            } catch (RimeUserDataException expected) {
                assertEquals(RimeUserDataException.Code.BUSY, expected.code());
            }
            File database = new File(session.userDirectory(), "local.userdb");
            assertTrue(database.mkdirs());
            Files.createSymbolicLink(
                    new File(database, "escape").toPath(), temporary.getRoot().toPath());
            try {
                session.checkpoint();
                throw new AssertionError("symlink must fail closed");
            } catch (RimeUserDataException expected) {
                assertEquals(RimeUserDataException.Code.LIMIT_EXCEEDED, expected.code());
            }
        }
    }
}
