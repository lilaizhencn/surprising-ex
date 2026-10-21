#!/usr/bin/env python3
"""Expose only Telegram's browser callback to a development HTTPS tunnel.

python3 deployment/telegram-callback-proxy.py --upstream http://127.0.0.1:19094
cloudflared tunnel --url http://127.0.0.1:19847
Authorization codes and state parameters are never logged.
"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlsplit
from urllib.request import Request, urlopen

CALLBACK = "/api/v1/auth/telegram/callback"


def handler(upstream):
    class CallbackProxy(BaseHTTPRequestHandler):
        def do_GET(self):
            path = urlsplit(self.path)
            if path.path != CALLBACK:
                self.reply(404, b"Not found")
                return
            if not parse_qs(path.query).get("state"):
                self.reply(400, b"Telegram callback ready. Start login from Surprising.")
                return
            try:
                request = Request(upstream + CALLBACK + "?" + path.query, headers={"Accept": "text/html"})
                with urlopen(request, timeout=20) as response:
                    body = response.read(65536)
                    self.reply(response.status, body, response.headers.get("Content-Type", "text/html; charset=utf-8"))
            except HTTPError as error:
                # Keep framework exception responses and request details off the public endpoint.
                self.reply(error.code, b"Login expired or invalid. Please return to Surprising.")
            except (URLError, TimeoutError, OSError):
                self.reply(503, b"Local login service is not ready. Please return to Surprising and try again.")

        def do_POST(self):
            self.reply(405, b"Method not allowed")

        def reply(self, status, body, content_type="text/plain; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    return CallbackProxy


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", default="http://127.0.0.1:19094")
    parser.add_argument("--port", type=int, default=19847)
    args = parser.parse_args()
    url = urlsplit(args.upstream)
    if url.scheme != "http" or url.hostname not in ("127.0.0.1", "localhost"):
        parser.error("upstream must be a local HTTP service")
    print(f"Callback proxy listening on 127.0.0.1:{args.port}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", args.port), handler(args.upstream.rstrip("/"))).serve_forever()
