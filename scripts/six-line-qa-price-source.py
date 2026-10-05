#!/usr/bin/env python3
"""Loopback-only deterministic external ticker for six-line Maven live QA.

Requires Python websockets. This is test infrastructure, never a production price source.
"""
import asyncio
import json
import time
from urllib.parse import urlparse, parse_qs
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

from websockets.asyncio.server import serve
from websockets.exceptions import ConnectionClosed


def ticker(path="/"):
    if urlparse(path).path == "/option":
        return {"symbol": "BTCUSDT", "markPrice": "1000", "indexPrice": "50000",
                "sameExpiryForwardPrice": "50500", "expiryTime": parse_qs(urlparse(path).query)["expiry"][0],
                "timestamp": int(time.time() * 1000)}
    return {"s": "BTCUSDT", "b": "49999.9", "a": "50000.1", "B": "100", "A": "100",
            "bidPrice": "49999.9", "askPrice": "50000.1", "E": int(time.time() * 1000)}


class TickerHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        payload = json.dumps(ticker(self.path)).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


async def stream(socket):
    try:
        while True:
            await socket.send(json.dumps(ticker(socket.request.path)))
            await asyncio.sleep(0.25)
    except ConnectionClosed:
        pass


async def main():
    rest = ThreadingHTTPServer(("127.0.0.1", 19195), TickerHandler)
    Thread(target=rest.serve_forever, daemon=True).start()
    try:
        async with serve(stream, "127.0.0.1", 19194):
            print("QA_PRICE_SOURCE_READY rest=19195 websocket=19194", flush=True)
            await asyncio.Future()
    finally:
        rest.shutdown()


if __name__ == "__main__":
    asyncio.run(main())
