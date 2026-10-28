package com.frenchreader.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Literal addresses only, so nothing here needs DNS or a network. */
class SourcePolicyTest {

    @Test
    void acceptsAnyHttpOrHttpsUrl() {
        for (String ok : new String[] {
                "https://ici.radio-canada.ca/nouvelle/1/x",
                "http://ici.radio-canada.ca/nouvelle/1/x",
                "https://www.lapresse.ca/actualites/2026-10-28/x.php",
                "https://example.fr:8443/a?b=c"}) {
            assertThat(SourcePolicy.check(ok)).as(ok).isPresent();
        }
    }

    @Test
    void refusesAddressesInsideThePrivateNetwork() throws Exception {
        for (String host : new String[] {
                "127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.10",
                "169.254.169.254", "100.64.0.1", "224.0.0.1", "::1", "fe80::1", "fd12::1"}) {
            assertThat(SourcePolicy.isPublic(InetAddress.getByName(host))).as(host).isFalse();
        }
        assertThatThrownBy(() -> SourcePolicy.requirePublicAddress(URI.create("http://127.0.0.1:8080/")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
        assertThatThrownBy(() -> SourcePolicy.requirePublicAddress(URI.create("http://[::ffff:192.168.0.1]/")))
                .hasMessageContaining("private network");
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        for (String host : new String[] {"8.8.8.8", "142.250.80.46", "100.128.0.1", "2001:4860:4860::8888"}) {
            assertThat(SourcePolicy.isPublic(InetAddress.getByName(host))).as(host).isTrue();
        }
    }
}
