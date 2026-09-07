#!/usr/bin/env python3
"""A stand-in for an Xboard panel's user API, for a demo without a panel (docs/connectors.md).

    scripts/fake-xboard.py 9124        # then XBOARD_BASE_URL=http://localhost:9124 and CONNECTOR_ALLOW_PRIVATE_NETWORKS=true

One customer, alice, signed in with the bearer token printed at start; every other token is
403, as the panel answers. Traffic in bytes, amounts in cents, times as Unix seconds.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

TOKEN = "1|xboardDemoTokenForAlice0123456789"
GB = 1024 ** 3
SUBSCRIBE = {"data": {"plan_id": 2, "expired_at": 1792108800, "u": 40 * GB, "d": int(61.5 * GB), "transfer_enable": 200 * GB,
                      "email": "alice@example.com", "reset_day": 9, "plan": {"id": 2, "name": "Pro 200G"}}}
INFO = {"data": {"email": "alice@example.com", "expired_at": 1792108800, "balance": 1250, "plan_id": 2}}
ORDERS = {"data": [{"trade_no": "2026090712345678", "status": 3, "total_amount": 1990, "created_at": 1788739200, "plan": {"name": "Pro 200G"}},
                   {"trade_no": "2026080712345678", "status": 2, "total_amount": 1990, "created_at": 1786060800, "plan": {"name": "Pro 200G"}}]}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlparse(self.path).path
        if path.endswith("/api/v1/guest/comm/config"):
            return self.reply(200, {"data": {"app_name": "Northwind Cloud"}})
        if self.headers.get("Authorization") != "Bearer " + TOKEN:
            return self.reply(403, {"message": "未登录或登陆已过期"})
        if path.endswith("/api/v1/user/getSubscribe"):
            return self.reply(200, SUBSCRIBE)
        if path.endswith("/api/v1/user/info"):
            return self.reply(200, INFO)
        if path.endswith("/api/v1/user/order/fetch"):
            return self.reply(200, ORDERS)
        return self.reply(404, {"message": "Not Found"})

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        sys.stderr.write("fake-xboard %s\n" % (fmt % args))


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9124
    print(f"fake Xboard on http://localhost:{port}; customer token {TOKEN}")
    HTTPServer(("0.0.0.0", port), Handler).serve_forever()
