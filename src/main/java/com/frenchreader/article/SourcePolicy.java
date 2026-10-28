package com.frenchreader.article;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The server fetches whatever URL the client hands it. Any page on the public web is fine, but
 * never an address inside the host's own network (localhost, the LAN, cloud metadata): without
 * that the endpoint is an open proxy into it.
 */
final class SourcePolicy {

    static final String RADIO_CANADA_HOST = "ici.radio-canada.ca";

    private SourcePolicy() {
    }

    /** Parses and validates the form of the URL; throws 400 for anything but an http(s) URL. */
    static URI require(String raw) {
        return check(raw).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Enter an http:// or https:// article URL"));
    }

    static Optional<URI> check(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.trim());
            boolean ok = ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getUserInfo() == null
                    && uri.getHost() != null && !uri.getHost().isEmpty();
            return ok ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the host and refuses it unless every address is on the public internet. Done right
     * before each fetch (and each redirect hop), so a public URL can't redirect inward either.
     */
    static void requirePublicAddress(URI uri) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown host: " + uri.getHost());
        }
        for (InetAddress a : addresses) {
            if (!isPublic(a)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "That URL points to a private network address");
            }
        }
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return false;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            return b[0] != 0                                  // 0.0.0.0/8
                    && !(b[0] == 100 && (b[1] & 0xC0) == 64); // 100.64.0.0/10, carrier-grade NAT
        }
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFE) != 0xFC;                     // fc00::/7, unique local
        }
        return true;
    }

    static boolean isRadioCanada(URI uri) {
        return RADIO_CANADA_HOST.equalsIgnoreCase(uri.getHost());
    }
}
