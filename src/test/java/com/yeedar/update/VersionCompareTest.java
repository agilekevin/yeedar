package com.yeedar.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionCompareTest {

    @Test
    @DisplayName("a higher patch or minor is newer")
    void higherIsNewer() {
        assertTrue(VersionCompare.isNewer("1.7.0", "1.6.0"));
        assertTrue(VersionCompare.isNewer("1.6.1", "1.6.0"));
    }

    @Test
    @DisplayName("1.10.0 is newer than 1.9.0")
    void doubleDigitSegmentsCompareNumerically() {
        // The whole reason this is a unit rather than String::compareTo. A
        // lexical compare puts "1.10.0" before "1.9.0" and silently stops
        // notifying anyone — and nothing surfaces that until the first
        // double-digit minor ships.
        assertTrue(VersionCompare.isNewer("1.10.0", "1.9.0"));
        assertFalse(VersionCompare.isNewer("1.9.0", "1.10.0"));
        assertTrue(VersionCompare.isNewer("1.6.10", "1.6.9"));
    }

    @Test
    @DisplayName("a major bump beats any number of minors")
    void majorDominates() {
        assertTrue(VersionCompare.isNewer("2.0.0", "1.99.99"));
        assertFalse(VersionCompare.isNewer("1.99.99", "2.0.0"));
    }

    @Test
    @DisplayName("equal versions are not newer")
    void equalIsNotNewer() {
        assertFalse(VersionCompare.isNewer("1.6.0", "1.6.0"));
    }

    @Test
    @DisplayName("an older candidate is not newer")
    void olderIsNotNewer() {
        // A local dev build ahead of the newest release must stay silent
        // rather than announce a downgrade.
        assertFalse(VersionCompare.isNewer("1.5.0", "1.6.0"));
        assertFalse(VersionCompare.isNewer("1.6.0", "1.7.0-SNAPSHOT"));
    }

    @Test
    @DisplayName("a leading v on either side is tolerated")
    void vPrefixIgnored() {
        // Release tags are "v1.7.0"; mod_version is "1.6.0". Both reach here.
        assertTrue(VersionCompare.isNewer("v1.7.0", "1.6.0"));
        assertTrue(VersionCompare.isNewer("1.7.0", "v1.6.0"));
        assertFalse(VersionCompare.isNewer("v1.6.0", "v1.6.0"));
    }

    @Test
    @DisplayName("missing trailing segments count as zero")
    void differingSegmentCounts() {
        assertFalse(VersionCompare.isNewer("1.6", "1.6.0"));
        assertFalse(VersionCompare.isNewer("1.6.0", "1.6"));
        assertTrue(VersionCompare.isNewer("1.7", "1.6.9"));
    }

    @Test
    @DisplayName("build suffixes are ignored, not compared")
    void suffixesIgnored() {
        // "1.6.0+1.21.8" and "1.6.0-SNAPSHOT" are the same release for our
        // purposes. Trying to order suffixes is semver's hardest corner and
        // buys nothing here.
        assertFalse(VersionCompare.isNewer("1.6.0-SNAPSHOT", "1.6.0"));
        assertTrue(VersionCompare.isNewer("1.7.0+build.4", "1.6.0"));
    }

    @Test
    @DisplayName("unparseable input never notifies")
    void garbageIsNotNewer() {
        // A malformed tag is not worth a wrong message. Silence is the only
        // safe answer, in both directions, including for null.
        assertFalse(VersionCompare.isNewer("banana", "1.6.0"));
        assertFalse(VersionCompare.isNewer("1.7.0", "banana"));
        assertFalse(VersionCompare.isNewer("", "1.6.0"));
        assertFalse(VersionCompare.isNewer(null, "1.6.0"));
        assertFalse(VersionCompare.isNewer("1.7.0", null));
    }
}
