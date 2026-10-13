package com.frenchreader.article;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The server fetches whatever URL the client hands it, so restrict that to the one site we have
 * extraction rules for. Without this the endpoint is an open proxy into the host's network.
 */
final class SourcePolicy {

    static final String HOST = "ici.radio-canada.ca";
    static final String SOURCE_NAME = "Radio-Canada";

    private SourcePolicy() {
    }

    /** Parses and validates; throws 400 for anything that is not an https Radio-Canada URL. */
    static URI require(String raw) {
        return check(raw).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Only https://" + HOST + "/ article URLs are supported"));
    }

    static Optional<URI> check(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(raw.trim());
            boolean ok = "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getUserInfo() == null
                    && HOST.equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443);
            return ok ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
