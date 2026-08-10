package com.opentypeless.android.recognition;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Set;

public final class CallerPackageAllowlistTest {
    @Test
    public void parsesWhitespaceCommaAndNormalizesCase() {
        assertEquals(
                Set.of("com.example.keyboard", "org.example.voice"),
                CallerPackageAllowlist.parse(
                        " Com.Example.Keyboard,\norg.example.voice\ncom.example.keyboard "));
    }

    @Test
    public void rejectsInvalidPackageNames() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CallerPackageAllowlist.parse("com.example.valid\nnot a package"));
    }

    @Test
    public void rejectsUnboundedCallerLists() {
        StringBuilder packages = new StringBuilder();
        for (int index = 0; index <= 100; index++) {
            packages.append("com.example.app").append(index).append('\n');
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> CallerPackageAllowlist.parse(packages.toString()));
    }
}
