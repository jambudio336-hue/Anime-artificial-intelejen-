#!/usr/bin/env python3
"""
kiplay_scan.py - recon ringan buat Agent Mas Kiplay.
Mode: port, banner, sub, dir.
"""

import socket
import ssl
import sys
import concurrent.futures
from urllib.parse import urljoin

TIMEOUT = 0.7
WORKERS = 200
UA = "Mozilla/5.0 (Linux; Android 14) KiplayScan/6.0"

WORDLIST = [
    "admin", "api", "assets", "backup", "config", "dashboard", "db",
    "debug", "dev", "docs", "download", "env", "files", "graphql",
    "health", "img", "internal", "login", "logs", "old", "panel",
    "phpmyadmin", "private", "robots.txt", "sitemap.xml", "staging",
    "static", "status", "swagger", "test", "tmp", "upload", "v1", "v2",
    "wp-admin", "wp-login.php", ".git/HEAD", ".env",
]


def probe(host, port, timeout=TIMEOUT):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(timeout)
        try:
            s.connect((host, port))
            return port
        except OSError:
            return None


def scan(host, ports):
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as ex:
        hasil = ex.map(lambda p: probe(host, p), ports)
        return sorted(p for p in hasil if p)


def banner(host, port, timeout=2.0):
    try:
        with socket.create_connection((host, port), timeout=timeout) as s:
            if port in (80, 8080, 8000, 443, 8443):
                req = "HEAD / HTTP/1.1\r\nHost: %s\r\nUser-Agent: %s\r\nConnection: close\r\n\r\n" % (host, UA)
                s.sendall(req.encode())
            elif port in (21, 25, 110, 143, 3306, 5432, 6379):
                pass
            else:
                s.sendall(b"\r\n")
            data = s.recv(1024)
            return data.decode("utf-8", "replace").strip().splitlines()[0][:120]
    except ssl.SSLError:
        return "(butuh TLS)"
    except OSError as e:
        return "(gagal: %s)" % e.__class__.__name__
    except IndexError:
        return "(tanpa banner)"


def subdomain(domain):
    def cek(sub):
        fqdn = "%s.%s" % (sub, domain)
        try:
            return fqdn, socket.gethostbyname(fqdn)
        except OSError:
            return None

    with concurrent.futures.ThreadPoolExecutor(max_workers=60) as ex:
        for r in ex.map(cek, WORDLIST):
            if r:
                print("[+] %-40s %s" % (r[0], r[1]))


def dirlist(base):
    import urllib.request
    import urllib.error

    if not base.endswith("/"):
        base += "/"

    def cek(path):
        url = urljoin(base, path)
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=4) as r:
                return path, r.status, r.headers.get("Content-Length", "-")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                return path, e.code, "-"
        except Exception:
            return None
        return None

    with concurrent.futures.ThreadPoolExecutor(max_workers=40) as ex:
        for r in ex.map(cek, WORDLIST):
            if r:
                print("[%s] %-30s len=%s" % (r[1], r[0], r[2]))


def main(argv):
    if len(argv) < 3:
        print(__doc__)
        return 1

    mode = argv[1].lower()

    if mode == "port":
        host = argv[2]
        lo = int(argv[3]) if len(argv) > 3 else 1
        hi = int(argv[4]) if len(argv) > 4 else 1024
        print("[*] scan %s port %d-%d" % (host, lo, hi))
        hasil = scan(host, range(lo, hi + 1))
        print("\n".join("[+] %d terbuka" % p for p in hasil) or "[-] semua tertutup")
        return 0

    if mode == "banner":
        for p in map(int, argv[3:]):
            print("[%d] %s" % (p, banner(argv[2], p)))
        return 0

    if mode == "sub":
        print("[*] subdomain %s" % argv[2])
        subdomain(argv[2])
        return 0

    if mode == "dir":
        print("[*] dirlist %s" % argv[2])
        dirlist(argv[2])
        return 0

    print("mode tidak dikenal: %s" % mode)
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except KeyboardInterrupt:
        print("\n[!] dihentikan")
