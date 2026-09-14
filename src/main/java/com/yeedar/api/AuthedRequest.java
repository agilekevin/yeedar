package com.yeedar.api;

import com.yeedar.update.ModVersion;

import java.net.URI;
import java.net.http.HttpRequest;

/**
 * The one place a Yeedar request to YeetVis is built.
 *
 * <p>Before this, the token header was set at seven call sites across three
 * classes. That was survivable while the token was the only header that
 * mattered; it stopped being survivable once the server started reading a
 * version header too. A request missing the version is indistinguishable from
 * a stale client once the server has a minimum set, so a single forgotten call
 * site would present as one feature mysteriously returning 426 while the rest
 * kept working.
 *
 * <p>The version comes from mod metadata via {@link ModVersion}, so it cannot
 * drift from what was actually built.
 */
public final class AuthedRequest {

    /** Yeedar's version. The server reads this to enforce a minimum. */
    public static final String VERSION_HEADER = "X-Yeedar-Version";
    /** Proves which Discord account is behind the request. */
    public static final String TOKEN_HEADER = "X-Yeedar-Token";

    private AuthedRequest() {}

    /**
     * A builder carrying both identity headers. Callers add the method, the
     * body, and Content-Type if they send one.
     */
    public static HttpRequest.Builder to(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header(TOKEN_HEADER, token)
                .header(VERSION_HEADER, ModVersion.current());
    }
}
