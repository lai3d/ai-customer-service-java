package dev.merlionos.customerservice.tenancy;

import java.time.Instant;

/** A customer. Everything a customer owns carries {@link #id()}; see ADR 002. */
public record Tenant(String id, String name, boolean enabled, Instant createdAt) {

    /** The tenant a fresh install has, and the owner of everything that predates tenancy. */
    public static final String DEFAULT = "default";
}
