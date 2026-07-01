#!/usr/bin/env python3
import argparse
import base64
import json
import os
import socket
import struct
import time
import urllib.parse


def read_exact(sock, length):
    chunks = []
    remaining = length
    while remaining > 0:
        chunk = sock.recv(remaining)
        if not chunk:
            raise ConnectionError("websocket connection closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def connect(url, headers):
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "ws":
        raise ValueError("only ws:// URLs are supported")
    host = parsed.hostname
    port = parsed.port or 80
    path = parsed.path or "/"
    if parsed.query:
        path += "?" + parsed.query
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    sock = socket.create_connection((host, port), timeout=5)
    sock.settimeout(1)
    request_headers = [
        f"GET {path} HTTP/1.1",
        f"Host: {host}:{port}",
        "Upgrade: websocket",
        "Connection: Upgrade",
        f"Sec-WebSocket-Key: {key}",
        "Sec-WebSocket-Version: 13",
    ]
    request_headers.extend(headers)
    request = "\r\n".join(request_headers) + "\r\n\r\n"
    sock.sendall(request.encode("ascii"))
    response = b""
    while b"\r\n\r\n" not in response:
        response += sock.recv(4096)
        if len(response) > 65536:
            raise ConnectionError("websocket handshake response too large")
    status = response.split(b"\r\n", 1)[0]
    if b" 101 " not in status:
        raise ConnectionError(f"websocket handshake failed: {status.decode('ascii', 'replace')}")
    return sock


def send_frame(sock, opcode, payload=b""):
    first = 0x80 | opcode
    mask = os.urandom(4)
    length = len(payload)
    if length < 126:
        header = struct.pack("!BB", first, 0x80 | length)
    elif length <= 0xFFFF:
        header = struct.pack("!BBH", first, 0x80 | 126, length)
    else:
        header = struct.pack("!BBQ", first, 0x80 | 127, length)
    masked = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    sock.sendall(header + mask + masked)


def send_text(sock, value):
    send_frame(sock, 0x1, value.encode("utf-8"))


def send_pong(sock, payload):
    send_frame(sock, 0xA, payload)


def read_frame(sock):
    header = read_exact(sock, 2)
    first, second = header
    opcode = first & 0x0F
    masked = bool(second & 0x80)
    length = second & 0x7F
    if length == 126:
        length = struct.unpack("!H", read_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(sock, 8))[0]
    mask = read_exact(sock, 4) if masked else None
    payload = read_exact(sock, length) if length else b""
    if mask:
        payload = bytes(byte ^ mask[index % 4] for index, byte in enumerate(payload))
    return opcode, payload


def capture_once(args, commands, headers, output):
    sock = connect(args.url, headers)
    try:
        for command in commands:
            send_text(sock, json.dumps(command, separators=(",", ":")))
        while time.time() < args.deadline and not os.path.exists(args.stop_file):
            try:
                opcode, payload = read_frame(sock)
            except socket.timeout:
                continue
            if opcode == 0x1:
                output.write(payload.decode("utf-8") + "\n")
                output.flush()
            elif opcode == 0x8:
                raise ConnectionError("server closed websocket")
            elif opcode == 0x9:
                send_pong(sock, payload)
    finally:
        try:
            sock.close()
        except OSError:
            pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--subscribe", action="append", default=[])
    parser.add_argument("--header", action="append", default=[])
    parser.add_argument("--output", required=True)
    parser.add_argument("--stop-file", required=True)
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    args.deadline = time.time() + args.timeout

    commands = [json.loads(value) for value in args.subscribe]
    headers = args.header
    backoff = 0.25
    with open(args.output, "a", encoding="utf-8") as output:
        while time.time() < args.deadline and not os.path.exists(args.stop_file):
            try:
                capture_once(args, commands, headers, output)
                backoff = 0.25
            except Exception as exc:
                output.write(json.dumps({"op": "capture_error", "message": str(exc)}) + "\n")
                output.flush()
                time.sleep(backoff)
                backoff = min(backoff * 2, 5.0)


if __name__ == "__main__":
    main()
