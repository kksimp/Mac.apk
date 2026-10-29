#!/usr/bin/env python3
"""Local HTTP CONNECT/SOCKS5 fixtures for production Java proxy paths.

All requested destinations are mapped to the local fixture servers. No Google
connection, device registration, system proxy change or real credential is used.
"""
import contextlib
import os
from pathlib import Path
import socket
import socketserver
import ssl
import struct
import subprocess
import sys
import threading
import time


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise EOFError()
        data.extend(part)
    return bytes(data)


def head(sock):
    data = bytearray()
    while not data.endswith(b"\r\n\r\n"):
        if len(data) >= 8192:
            raise ValueError("fixture header limit")
        data.extend(exact(sock, 1))
    return bytes(data)


def relay(a, b):
    def copy(source, target):
        try:
            while data := source.recv(16384):
                target.sendall(data)
        except (OSError, ssl.SSLError):
            pass
        finally:
            with contextlib.suppress(OSError):
                target.shutdown(socket.SHUT_WR)
    thread = threading.Thread(target=copy, args=(a, b), daemon=True)
    thread.start()
    copy(b, a)
    thread.join(timeout=3)


class TCP(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class Fixtures:
    def __init__(self, pem):
        self.counts = dict(http=0, socks=0, tls=0, plain=0)
        self.lock = threading.Lock()
        self.servers = []
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(pem)

    def count(self, name):
        with self.lock:
            self.counts[name] += 1

    def serve(self, handler):
        server = TCP(("127.0.0.1", 0), handler)
        self.servers.append(server)
        threading.Thread(target=server.serve_forever, kwargs=dict(poll_interval=.03), daemon=True).start()
        return server.server_address[1]

    def start(self):
        fixture = self

        class Plain(socketserver.BaseRequestHandler):
            def handle(self):
                try:
                    self.request.settimeout(3)
                    head(self.request)
                    fixture.count("plain")
                    self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                except (OSError, EOFError, ValueError):
                    pass

        class TLS(socketserver.BaseRequestHandler):
            def handle(self):
                try:
                    self.request.settimeout(3)
                    with fixture.context.wrap_socket(self.request, server_side=True) as stream:
                        fixture.count("tls")
                        head(stream)  # MCS test only handshakes, then closes.
                        stream.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                except (OSError, EOFError, ValueError):
                    pass

        self.plain = self.serve(Plain)
        self.tls = self.serve(TLS)

        def http_handler(reject=False, silent=False):
            class HTTP(socketserver.BaseRequestHandler):
                def handle(self):
                    fixture.count("http")
                    try:
                        self.request.settimeout(3)
                        data = head(self.request)
                        if silent:
                            time.sleep(2)
                            return
                        if reject:
                            self.request.sendall(b"HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n")
                            return
                        if data.startswith(b"CONNECT "):
                            # Refuse unexpected targets rather than resolving
                            # or contacting anything outside this fixture.
                            authority = data.split(b" ", 2)[1].decode("ascii")
                            host, port = authority.rsplit(":", 1)
                            if host not in ("192.0.2.123", "mtalk.google.com") or int(port) not in (fixture.tls, 5228, 443):
                                raise ValueError("unexpected CONNECT destination")
                            with socket.create_connection(("127.0.0.1", fixture.tls), timeout=2) as upstream:
                                self.request.sendall(b"HTTP/1.1 200 Connection established\r\n\r\n")
                                relay(self.request, upstream)
                        else:
                            if not data.startswith((f"GET http://192.0.2.123:{fixture.plain}/ ").encode("ascii")):
                                raise ValueError("unexpected HTTP proxy destination")
                            self.request.sendall(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK")
                    except (OSError, EOFError, ValueError):
                        pass
            return HTTP

        def socks_handler(reject=False):
            class SOCKS(socketserver.BaseRequestHandler):
                def handle(self):
                    fixture.count("socks")
                    try:
                        self.request.settimeout(3)
                        version, length = exact(self.request, 2)
                        methods = exact(self.request, length)
                        if version != 5 or 0 not in methods:
                            raise ValueError("SOCKS5 no-auth required")
                        self.request.sendall(b"\x05\x00")
                        version, command, reserved, kind = exact(self.request, 4)
                        if (version, command, reserved) != (5, 1, 0):
                            raise ValueError("unexpected SOCKS command")
                        if kind == 1:
                            host = socket.inet_ntop(socket.AF_INET, exact(self.request, 4))
                        elif kind == 3:
                            host = exact(self.request, exact(self.request, 1)[0]).decode("ascii")
                        elif kind == 4:
                            host = socket.inet_ntop(socket.AF_INET6, exact(self.request, 16))
                        else:
                            raise ValueError("SOCKS address kind")
                        port = struct.unpack("!H", exact(self.request, 2))[0]
                        if reject:
                            self.request.sendall(b"\x05\x05\x00\x01" + b"\0" * 6)
                            return
                        if host not in ("192.0.2.123", "mtalk.google.com", "2001:db8::123") or port not in (fixture.plain, fixture.tls, 5228, 443):
                            raise ValueError("unexpected SOCKS destination")
                        if host == "2001:db8::123" and kind != 4:
                            raise ValueError("IPv6 literal requires SOCKS ATYP4")
                        target = fixture.plain if port == fixture.plain else fixture.tls
                        with socket.create_connection(("127.0.0.1", target), timeout=2) as upstream:
                            self.request.sendall(b"\x05\x00\x00\x01" + b"\0" * 6)
                            relay(self.request, upstream)
                    except (OSError, EOFError, ValueError):
                        pass
            return SOCKS

        self.http = self.serve(http_handler())
        self.socks = self.serve(socks_handler())
        self.http_reject = self.serve(http_handler(reject=True))
        self.socks_reject = self.serve(socks_handler(reject=True))
        self.silent = self.serve(http_handler(silent=True))
        return self

    def close(self):
        for server in self.servers:
            server.shutdown()
            server.server_close()


def main():
    java, patch, cp, library, p12, pem, temporary = sys.argv[1:]
    root = Path(temporary)
    fixture = Fixtures(pem).start()
    try:
        scenarios = [
            ("none", None, fixture.http, "none"),
            ("direct", "mode=direct\n", fixture.http, "direct"),
            ("http", f"mode=http\nhost=127.0.0.1\nport={fixture.http}\n", fixture.http, "http"),
            ("socks5", f"mode=socks5\nhost=127.0.0.1\nport={fixture.socks}\n", fixture.socks, "socks5"),
            ("system", "mode=system\n", fixture.http, "system"),
            ("system-native", "mode=system\n", fixture.http, "system-native"),
            ("http-reject", f"mode=http\nhost=127.0.0.1\nport={fixture.http_reject}\n", fixture.http_reject, "reject"),
            ("socks-reject", f"mode=socks5\nhost=127.0.0.1\nport={fixture.socks_reject}\n", fixture.socks_reject, "reject"),
            ("timeout", f"mode=http\nhost=127.0.0.1\nport={fixture.silent}\n", fixture.silent, "timeout"),
            ("invalid", "mode=http\nhost=localhost\nport=0\n", fixture.http, "invalid"),
            ("resolved-root", "mode=direct\n", fixture.http, "direct"),
        ]
        env_root = root / "env-root" / ".macapk"
        env_root.mkdir(parents=True)
        (env_root / "proxy.properties").write_text(f"mode=http\nhost=127.0.0.1\nport={fixture.http}\n")
        for label, text, proxy_port, mode in scenarios:
            app_root = root / label
            (app_root / ".macapk").mkdir(parents=True)
            if text is not None:
                (app_root / ".macapk/proxy.properties").write_text(text)
            before = fixture.counts.copy()
            cmd = [java, "--patch-module", f"jdk.unsupported={patch}", "--add-opens", "java.base/jdk.internal.misc=jdk.unsupported",
                   "--add-exports", "java.base/sun.net.www.protocol.http=ALL-UNNAMED", "--add-exports", "java.base/sun.net.www.protocol.https=ALL-UNNAMED",
                   f"-Dmacapk.android.root={app_root}", f"-Djavax.net.ssl.trustStore={p12}", "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStoreProvider=SUN", "-Djavax.net.ssl.trustStorePassword=changeit",
                   "-cp", cp, "HostProxyCompatibilitySmoke", library, mode, str(proxy_port), str(fixture.tls), str(fixture.plain)]
            env = {**os.environ, "MACAPK_ANDROID_ROOT": str(env_root.parent if label == "resolved-root" else app_root), "MACAPK_LOG_LEVEL": "7", "MACAPK_QUIET_CLASSLOAD": "1"}
            run = subprocess.run(cmd, env=env, capture_output=True, text=True, timeout=20)
            if run.returncode:
                print(label + ": FAILED\n" + run.stdout + run.stderr)
                raise SystemExit(run.returncode)
            after = fixture.counts.copy()
            proxy_calls = after["http"] + after["socks"] - before["http"] - before["socks"]
            if label in ("none", "http", "socks5", "system", "http-reject", "socks-reject", "timeout"):
                assert proxy_calls > 0, label + ": expected real proxy connection"
            else:
                assert proxy_calls == 0, label + ": loopback/direct/invalid must not contact proxy"
            print(label + ": PASS (actual local proxy connections=" + str(proxy_calls) + ")", flush=True)
    finally:
        fixture.close()


if __name__ == "__main__":
    main()
