package com.yeedar.net;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.Locale;

/**
 * Whether Yeedar is connected to the server it is allowed to report on.
 *
 * <p>Everything Yeedar uploads is about EdenMC — the snitch formats it parses,
 * the namelayer groups it resolves, and the one map all of it lands on. Left
 * ungated the mod reports whatever server it happens to be running on, so
 * joining a friend's world with it installed quietly files that world's
 * players and terrain into Eden's map. Nobody consented to that on either end.
 *
 * <p>The check is split in two on purpose. {@link #isEdenAddress} is the rule,
 * and is plain Java so it can be tested without a running game;
 * {@link #connected} is the lookup, and is the part that needs a client.
 */
public final class EdenServer {

    /** EdenMC, exactly. */
    public static final String HOST = "play.edenmc.world";

    private EdenServer() {}

    /**
     * Does this server address name EdenMC?
     *
     * <p>Compares the host alone: the port, letter case, a trailing root dot
     * and surrounding whitespace are all noise, because Direct Connect stores
     * whatever the player typed rather than anything normalised.
     *
     * <p>Everything past that normalising is an equality check, not a suffix
     * one. {@code play.edenmc.world.example.com} ends in nothing Eden owns,
     * and an {@code endsWith} would take it. The cost of the strict rule is
     * that Eden moving to another subdomain stops uploads until this constant
     * is changed — a visible, fixable failure, where the loose rule's failure
     * is data going somewhere it should not, silently.
     */
    public static boolean isEdenAddress(String address) {
        if (address == null) return false;
        String host = address.trim().toLowerCase(Locale.ROOT);
        // Strip the port. Splitting on the first colon is right for a
        // hostname, and a bracketed IPv6 literal is not one, so it falls out
        // as a non-match either way.
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        // A fully-qualified name ends in the root dot and resolves the same.
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.equals(HOST);
    }

    /**
     * Is the client on EdenMC right now?
     *
     * <p>False for singleplayer, for a LAN world, for the main menu, and for
     * every other server. Callers treat that as "do not upload" and say
     * nothing to the player beyond the one notice on joining — see the gate's
     * design note.
     */
    public static boolean connected() {
        ServerInfo entry = remoteEntry();
        return entry != null && isEdenAddress(entry.address);
    }

    /**
     * Is the client on someone else's server at all?
     *
     * <p>Distinct from {@link #connected}: this is true on any multiplayer
     * server, Eden or not. It separates "somewhere uploads could have been
     * expected" from singleplayer and LAN, where they never would be, which
     * is the difference between a useful notice and a nag.
     */
    public static boolean onRemoteServer() {
        return remoteEntry() != null;
    }

    /** The entry for the multiplayer server we are on, or null if we are not
     *  on one. */
    private static ServerInfo remoteEntry() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return null;
        // An integrated server keeps the entry for whatever the player last
        // joined, so this has to be asked before the address is.
        if (client.isInSingleplayer()) return null;
        ServerInfo entry = client.getCurrentServerEntry();
        if (entry == null) return null;
        if (entry.isLocal() || entry.isRealm()) return null;
        return entry;
    }
}
