package com.yeedar.net;

/**
 * Decides whether to tell the player Yeedar is not uploading from here.
 *
 * <p>The gate is deliberately silent everywhere else — no refusal message on a
 * gated command, nothing per sighting. That leaves one question unanswered
 * ("why is nothing reaching the map?"), and this is the one line that answers
 * it. Said once, at the point the player starts playing, and then not again.
 *
 * <p>Free of Minecraft types so the rule can be tested on its own. The caller
 * owns the message and the flag; this owns the decision.
 */
public final class OffEdenNotice {

    private OffEdenNotice() {}

    /**
     * True when the player should be told, right now, that nothing is being
     * uploaded from the server they are on.
     *
     * <p>Once per launch, not once per join. People relog constantly, and a
     * line that returns every session is one they stop reading — the same
     * reasoning that keeps the update notice quiet after its first showing.
     * The first non-Eden join of a session gets it, whether or not the
     * session started on Eden.
     *
     * @param onRemoteServer on a real multiplayer server; false for
     *                       singleplayer, a LAN world, and the main menu.
     *                       Those stay quiet: nobody loading their own world
     *                       is waiting for it to appear on Eden's map, and
     *                       nagging on every test world is how a notice gets
     *                       tuned out before it is ever needed.
     * @param onEden         that server is EdenMC, where uploads work normally
     * @param alreadyShown   this launch has already said it
     */
    public static boolean shouldNotify(boolean onRemoteServer, boolean onEden,
                                       boolean alreadyShown) {
        if (alreadyShown) return false;
        if (!onRemoteServer) return false;
        return !onEden;
    }
}
