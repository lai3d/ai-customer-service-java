package dev.merlionos.customerservice.rag;

import dev.merlionos.customerservice.rag.api.KnowledgeRuleException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * What a URL import may fetch. The URL is typed by a tenant's admin and fetched by the
 * knowledge role from inside the deployment's network, which is the shape of a server-side
 * request forgery: without this, {@code http://169.254.169.254/} or {@code http://postgres:5432/}
 * would be read on the tenant's behalf. So: http or https only, no credentials in the URL,
 * and every address the host resolves to must be a public one -- loopback, link-local
 * (the cloud metadata address lives there), the private ranges, carrier-grade NAT, IPv6
 * unique-local and multicast are all refused. The check runs again on every redirect hop,
 * since a public page may redirect anywhere.
 *
 * <p>Known limit: the address is resolved here and again by the HTTP client, so a name
 * whose answer changes between the two (DNS rebinding) is not caught. Pinning the resolved
 * address would need a client that lets the connection address differ from the host header.
 */
class SourceGuard {

    private final boolean allowPrivateNetworks;

    SourceGuard(boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    /** The URL as a URI, if an import may fetch it; a {@link KnowledgeRuleException} says why not. */
    URI check(String url) {
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.strip()).normalize();
        }
        catch (IllegalArgumentException e) {
            throw new KnowledgeRuleException("not a URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new KnowledgeRuleException("only http and https URLs can be imported");
        }
        if (uri.getRawUserInfo() != null) {
            throw new KnowledgeRuleException("a URL with credentials in it cannot be imported");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new KnowledgeRuleException("the URL names no host");
        }
        if (allowPrivateNetworks) {
            return uri;
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.equals("localhost") || name.endsWith(".localhost") || name.endsWith(".local") || name.endsWith(".internal")) {
            throw new KnowledgeRuleException("the URL points inside the deployment, which an import may not read: " + host);
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host.startsWith("[") ? host.substring(1, host.length() - 1) : host);
        }
        catch (UnknownHostException e) {
            throw new KnowledgeRuleException("the host cannot be resolved: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new KnowledgeRuleException("the URL points inside the deployment, which an import may not read: " + host);
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0                                   // 0.0.0.0/8, "this network"
                    && !(first == 100 && second >= 64 && second <= 127)   // 100.64.0.0/10, carrier-grade NAT
                    && !(first == 192 && second == 0 && (bytes[2] & 0xff) == 0)  // 192.0.0.0/24
                    && first != 240 && first < 240;             // 240.0.0.0/4, reserved
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) != 0xfc;                       // fc00::/7, unique local
        }
        return true;
    }
}
