#!/usr/bin/env python3
"""A stand-in for Shopify's Admin REST API, for a demo without a store (docs/connectors.md).

    scripts/fake-shopify.py 9123          # then SHOPIFY_BASE_URL=http://localhost:9123 for the app
                                          # (http://host.docker.internal:9123 from Compose)

Serves shop.json and orders.json?name= for four orders, in Shopify's shapes, and refuses any
token but shpat_demo_token_0123456789abcdef. The same four orders as the tests' FakeShopify.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse

TOKEN = "shpat_demo_token_0123456789abcdef"
ORDERS = {
    "#1001": {"id": 1, "name": "#1001", "created_at": "2026-08-27T10:15:00+08:00", "cancelled_at": None,
              "financial_status": "paid", "fulfillment_status": "fulfilled",
              "line_items": [{"title": "Noise-cancelling headphones", "quantity": 1}],
              "fulfillments": [{"tracking_company": "SingPost", "tracking_number": "SP884213906SG",
                                "shipment_status": "in_transit", "estimated_delivery_at": "2026-09-03T00:00:00+08:00"}]},
    "#1002": {"id": 2, "name": "#1002", "created_at": "2026-08-31T09:00:00+08:00", "cancelled_at": None,
              "financial_status": "paid", "fulfillment_status": None,
              "line_items": [{"title": "Cotton t-shirt (M, navy)", "quantity": 2}], "fulfillments": []},
    "#1003": {"id": 3, "name": "#1003", "created_at": "2026-08-18T09:00:00+08:00", "cancelled_at": None,
              "financial_status": "paid", "fulfillment_status": "fulfilled",
              "line_items": [{"title": "Espresso machine", "quantity": 1}],
              "fulfillments": [{"tracking_company": "DHL", "tracking_number": "JD0002088776", "shipment_status": "delivered"}]},
    "#1004": {"id": 4, "name": "#1004", "created_at": "2026-08-29T09:00:00+08:00", "cancelled_at": "2026-08-30T09:00:00+08:00",
              "financial_status": "refunded", "fulfillment_status": None,
              "line_items": [{"title": "Mechanical keyboard", "quantity": 1}], "fulfillments": []},
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlparse(self.path)
        if self.headers.get("X-Shopify-Access-Token") != TOKEN:
            return self.reply(401, {"errors": "[API] Invalid API key or access token"})
        if url.path.endswith("/shop.json"):
            return self.reply(200, {"shop": {"name": "Northwind Lamps", "myshopify_domain": "northwind-lamps.myshopify.com"}})
        if url.path.endswith("/orders.json"):
            name = parse_qs(url.query).get("name", [None])[0]
            order = ORDERS.get(name)
            return self.reply(200, {"orders": [order] if order else []})
        return self.reply(404, {"errors": "Not Found"})

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-shopify %s\n" % (fmt % args))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9123
    print(f"fake Shopify on http://localhost:{port}; token {TOKEN}")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
