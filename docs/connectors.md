# Order connectors: Shopify

Business plan step 2, second half. The `lookup_order_status` tool has always answered from
a mock; a customer's real question is about a real order. This is the first connector, a
tenant's Shopify store, and the seam it sits behind, so that the next one (Youzan, or
whatever the first pilot runs) is a second implementation and not a second tool.

## The seam

`OrderLookup.lookup(tenantId, orderNumber)` is what the tool calls; the tenant comes from
the request's API key through the tool context, never from a model argument, so a tenant
can only ever read its own orders. `ConnectorOrderLookup` routes: a tenant with a connector
goes to it; the default tenant without one keeps the bundled mock (the demo, the tests, a
laptop); any other tenant without one is told, as a tool result, that no order system is
connected, so the assistant offers a ticket instead of guessing.

A tool result is prompt, and it now spells out three outcomes rather than two: `found`,
`not_found` (ask the customer to check the number) and `unavailable` (the store did not
answer; say so, offer to try again or to raise a ticket, never say the order does not
exist). The tool description says the same, since the description is what the model reads.

## Shopify

`ShopifyOrderLookup` makes one call to the Admin REST API,
`GET /admin/api/{version}/orders.json?name=<order name>&status=any`, with the custom app's
access token in `X-Shopify-Access-Token`. A customer writes "1001" or "#1001"; the number is
tried as given and, if it has no `#`, with one. The store's answer is folded into the
`Order` the tool has always returned:

| Shopify | `Order` |
| --- | --- |
| `name` | `orderNumber` |
| `cancelled_at` set | `CANCELLED` |
| `financial_status` refunded or partially refunded | `RETURN_IN_PROGRESS` |
| `fulfillment_status` fulfilled or partial, last fulfillment `shipment_status` delivered | `DELIVERED` |
| … in_transit, out_for_delivery, attempted_delivery, ready_for_pickup | `IN_TRANSIT` |
| … anything else | `DISPATCHED` |
| no fulfillment | `PREPARING` |
| last fulfillment `tracking_company`, `tracking_number`, `estimated_delivery_at` | `carrier`, `trackingNumber`, `estimatedDelivery` |
| `line_items` | `summary`, "2 x Cotton t-shirt" |
| `created_at` | `placedOn` |

A refused token (401, 403) or an unreachable store is `unavailable` with a sentence the
model can pass on; nothing is thrown at the customer. The shop domain must be
`<store>.myshopify.com`: the Admin API lives nowhere else, and that is also what keeps the
connector from being a way to fetch anything.

## Configuring a tenant's store

In the operations admin, an admin of the tenant (or platform staff):

```
PUT  /admin/api/tenants/{id}/order-connector   {"kind":"shopify","shopDomain":"my-store.myshopify.com","accessToken":"shpat_…","apiVersion":"2025-07"}
GET  /admin/api/tenants/{id}/order-connector   -> the same with the token reduced to its last four characters, or 204
POST /admin/api/tenants/{id}/order-connector/test   -> {"ok":true,"shopName":"…"} using the stored token
DELETE /admin/api/tenants/{id}/order-connector
```

Each change is recorded in `admin_audit` as `connector_changed`. The token is a secret at
rest: with `ORDER_CONNECTOR_KEY` set (32 bytes, base64, `openssl rand -base64 32`) it is
stored AES-256-GCM encrypted with a fresh nonce; unset, it is stored as given and startup
says so, because a laptop demo should not have to mint a key and a deployment holding a
customer's store token should. A key can be introduced later; plain values still open.

### Getting a token from Shopify — the store owner does this

1. In the store's admin: **Settings → Apps and sales channels → Develop apps → Create an app**.
2. **Configure Admin API scopes**: `read_orders` (and `read_fulfillments` on stores that
   expose it separately). Nothing else; the connector only reads.
3. **Install app**, then **API credentials → Admin API access token**: shown once, `shpat_…`.
4. Paste the shop domain and the token into the admin as above; **test** answers with the
   store's name.

A Shopify Partner account gives a free development store with test orders, which is how to
demo this against a real Shopify before a pilot hands over a token.

### A demo without a store

`scripts/fake-shopify.py 9123` serves four orders in Shopify's shapes (`#1001` in transit
with SingPost tracking, `#1002` preparing, `#1003` delivered, `#1004` cancelled and
refunded) and accepts the token printed at start. Point the app at it with
`SHOPIFY_BASE_URL=http://localhost:9123` (`http://host.docker.internal:9123` from Compose),
configure the connector with any `*.myshopify.com` domain and that token, and ask the
assistant where order #1001 is.

## Verified

2026-09-07, against `scripts/fake-shopify.py` and the real model, through the admin API and
the public one: the connector configured and tested (the store's name back), "Where is my
order #1001 and when does it arrive?" answered with the carrier, the tracking number and the
estimated date from the store, "order 1001" without the hash found on the second try, and
"#7777" answered three times out of three with "I couldn't find an order matching #7777",
the tool's `not_found` reaching the customer as a request to check the number. On the first
of those walks the model dressed the number as `ORD-1001` after the tool's example, which is
why the parameter description now says "exactly as the customer wrote it" and the connector
also tries the digits alone. One blocking-path turn on that first walk came back with an
empty answer and no error, like evaluation case 30 in [evaluation.md](evaluation.md); not
reproduced since, recorded here so the next occurrence is not the first.

## Not here, deliberately

- **Proving the customer owns the order.** Anyone who knows a number can ask about it, as
  with the mock. Matching the order's email to a verified customer, or a signed widget
  session, is the next step and a decision for the pilot: some stores want exactly this
  openness, most will not.
- Writes: cancelling, changing an address. The tool reads; the ticket is how a change is asked for.
- A second connector. The seam is one interface and one routing class; Youzan or WooCommerce
  is a class next to `shopify/`.
- Rate limiting against Shopify's bucket (2 calls/second on REST). One call per turn is far
  under it; a busy tenant would need a limiter per store.
