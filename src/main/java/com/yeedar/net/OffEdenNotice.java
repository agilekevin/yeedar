package com.yeedar.net;

/**
 * Decides whether to tell the player Yeedar is not uploading from here.
 *
 * <p>The gate answers for itself only where the player asked it something —
 * {@code /yeedar launch} says why it refused. Nothing is said per sighting or
 * per upload. That leaves one question unanswered ("why is nothing reaching
 * the map?"), and this is the line that answers it, once each time the player
 * joins somewhere it applies.
 *
 * <p>Free of Minecraft types so the rule can be tested on its own. The caller
 * owns the message and the timing; this owns the decision.
 */
public final class OffEdenNotice {

    private OffEdenNotice() {}

    /**
     * True when the player should be told, right now, that nothing is being
     * uploaded from the server they are on.
     *
     * <p>Once per join. The caller arms this from the join event and fires it
     * a single time per connection, so "once" is the countdown's job and this
     * keeps no memory of its own — there is no flag here to fall out of step
     * with the connection it describes.
     *
     * @param onRemoteServer on a real multiplayer server; false for
     *                       singleplayer, a LAN world, and the main menu.
     *                       Those stay quiet: nobody loading their own world
     *                       is waiting for it to appear on Eden's map, and
     *                       nagging on every test world is how a notice gets
     *                       tuned out before it is ever needed.
     * @param onEden         that server is EdenMC, where uploads work normally
     */
    public static boolean shouldNotify(boolean onRemoteServer, boolean onEden) {
        if (!onRemoteServer) return false;
        return !onEden;
    }
}
