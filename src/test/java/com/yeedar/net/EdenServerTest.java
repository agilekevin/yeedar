package com.yeedar.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides whether Yeedar is allowed to upload anything.
 *
 * <p>Worth testing properly because both ways of getting it wrong are quiet.
 * Too strict and uploads stop with no message — that is the design. Too loose
 * and another server's players end up on Eden's map, which is the thing this
 * whole gate exists to prevent.
 */
class EdenServerTest {

    @Test
    @DisplayName("the address the client stores for Eden matches")
    void plainAddressMatches() {
        assertTrue(EdenServer.isEdenAddress("play.edenmc.world"));
    }

    @Test
    @DisplayName("an explicit port does not change which server it is")
    void portIsIgnored() {
        // Direct Connect keeps whatever the player typed, and plenty of
        // people type the port even when it is the default.
        assertTrue(EdenServer.isEdenAddress("play.edenmc.world:25565"));
        assertTrue(EdenServer.isEdenAddress("play.edenmc.world:25566"));
    }

    @Test
    @DisplayName("hostnames are case-insensitive")
    void caseIsIgnored() {
        assertTrue(EdenServer.isEdenAddress("Play.EdenMC.World"));
        assertTrue(EdenServer.isEdenAddress("PLAY.EDENMC.WORLD"));
    }

    @Test
    @DisplayName("a trailing dot is the same host")
    void trailingDotIsIgnored() {
        // A fully-qualified name ends in a root dot and resolves identically.
        assertTrue(EdenServer.isEdenAddress("play.edenmc.world."));
        assertTrue(EdenServer.isEdenAddress("play.edenmc.world.:25565"));
    }

    @Test
    @DisplayName("surrounding whitespace does not matter")
    void whitespaceIsTrimmed() {
        assertTrue(EdenServer.isEdenAddress("  play.edenmc.world  "));
    }

    @Test
    @DisplayName("no address at all is not Eden")
    void missingAddressIsNotEden() {
        // Singleplayer has no server entry; a half-torn-down connection can
        // hand back an empty one.
        assertFalse(EdenServer.isEdenAddress(null));
        assertFalse(EdenServer.isEdenAddress(""));
        assertFalse(EdenServer.isEdenAddress("   "));
        assertFalse(EdenServer.isEdenAddress(":25565"));
    }

    @Test
    @DisplayName("another server is not Eden")
    void otherServersAreNotEden() {
        assertFalse(EdenServer.isEdenAddress("localhost"));
        assertFalse(EdenServer.isEdenAddress("127.0.0.1:25565"));
        assertFalse(EdenServer.isEdenAddress("mc.hypixel.net"));
    }

    @Test
    @DisplayName("a near-miss hostname is not Eden")
    void nearMissesAreNotEden() {
        // The match is exact by decision: a different Eden subdomain stops
        // uploads, which is visible and fixable, where accepting a lookalike
        // is neither.
        assertFalse(EdenServer.isEdenAddress("edenmc.world"));
        assertFalse(EdenServer.isEdenAddress("mc.edenmc.world"));
        assertFalse(EdenServer.isEdenAddress("test.edenmc.world"));
    }

    @Test
    @DisplayName("a hostname that merely contains Eden's is not Eden")
    void lookalikeDomainsAreNotEden() {
        // The reason this is an equality check and not a contains or an
        // endsWith. Both of these are trivially registerable by someone who
        // wants Yeedar pointed at their server.
        assertFalse(EdenServer.isEdenAddress("play.edenmc.world.example.com"));
        assertFalse(EdenServer.isEdenAddress("notplay.edenmc.world"));
        assertFalse(EdenServer.isEdenAddress("play-edenmc.world"));
    }
}
