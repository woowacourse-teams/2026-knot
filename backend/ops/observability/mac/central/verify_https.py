"""Synthetic Nginx HTTPS + existing mTLS ingress regression; Docker and openssl required.

Uses disposable certificates and mock upstreams. This does not test public DNS,
Cloudflare settings, certificate issuance, production authentication, or host ACLs.
"""
from pathlib import Path
import subprocess
import tempfile

import render_https


ROOT = Path(__file__).resolve().parents[2]


def run(*command):
    return subprocess.check_output(command, stderr=subprocess.STDOUT, text=True).strip()


def main():
    with tempfile.TemporaryDirectory(prefix="knot-https-805-") as directory:
        work = Path(directory)
        name = work.name
        def certificate(cn, stem, server=False):
            run("openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=" + cn,
                "-keyout", str(work / (stem + ".key")), "-out", str(work / (stem + ".csr")))
            ext = work / (stem + ".ext")
            ext.write_text("extendedKeyUsage=" + ("serverAuth\nsubjectAltName=DNS:grafana.example.com,DNS:ingest.example.com\n"
                           if server else "clientAuth\n"))
            run("openssl", "x509", "-req", "-in", str(work / (stem + ".csr")), "-CA", str(work / "ca.crt"),
                "-CAkey", str(work / "ca.key"), "-CAcreateserial", "-days", "1", "-extfile", str(ext),
                "-out", str(work / (stem + ".crt")))
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=fixture",
            "-keyout", str(work / "ca.key"), "-out", str(work / "ca.crt"))
        certificate("grafana.example.com", "server", True)
        certificate("knot-mac-alloy", "mac")
        certificate("knot-dev-alloy", "dev")
        files = render_https.render("grafana.example.com", "192.0.2.1/32", "192.0.2.2/32",
                                    "ens5", "173.245.48.0/20")
        for filename, content in files.items():
            (work / filename).write_text(content)
        public = files["knot-grafana-public.conf"].replace(
            "/etc/letsencrypt/live/grafana.example.com/fullchain.pem", "/fixture/server.crt").replace(
            "/etc/letsencrypt/live/grafana.example.com/privkey.pem", "/fixture/server.key").replace(
            "/etc/nginx/snippets/knot-cloudflare-realip.conf", "/fixture/knot-cloudflare-realip.conf")
        (work / "public.conf").write_text(public)
        ingest = (ROOT / "nginx/ingest.conf").read_text().replace(
            "/opt/knot-observability/tls", "/fixture").replace(
            "/etc/nginx/snippets/knot-log-receipt-locations.conf", "/fixture/log-receipt-locations.conf").replace(
            "include /etc/nginx/snippets/knot-ingest-allow.conf;", "allow all; # isolated loopback-published fixture")
        (work / "log-receipt-locations.conf").write_text(
            (ROOT / "nginx/log-receipt-locations.conf").read_text())
        (work / "ingest.conf").write_text(ingest)
        (work / "nginx.conf").write_text('events {}\nhttp { access_log off; include /fixture/public.conf; '
            'include /fixture/ingest.conf; server { listen 127.0.0.1:3000; location / { return 200 "mock grafana"; } } '
            'server { listen 127.0.0.1:9090; location /api/v1/write { return 204; } } }\n')
        try:
            run("docker", "run", "-d", "--name", name, "-p", "127.0.0.1:14443:443", "-p", "127.0.0.1:14080:80",
                "-v", str(work) + ":/fixture:ro", "nginx:1.28-alpine", "nginx", "-c", "/fixture/nginx.conf", "-g", "daemon off;")
            run("docker", "exec", name, "nginx", "-t", "-c", "/fixture/nginx.conf")
            def status(host, path, method="GET", cert=None, http=False):
                port, protocol = (14080, "http") if http else (14443, "https")
                cmd = ["curl", "--noproxy", "*", "--silent", "--show-error", "--max-time", "5", "--cacert", str(work / "ca.crt"),
                       "--resolve", f"{host}:{port}:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}", "-X", method]
                if cert:
                    cmd += ["--cert", str(work / (cert + ".crt")), "--key", str(work / (cert + ".key"))]
                return run(*cmd, f"{protocol}://{host}:{port}{path}")
            cases = [(("grafana.example.com", "/"), "200"),
                     (("grafana.example.com", "/metrics"), "404"),
                     (("grafana.example.com", "/debug/pprof"), "404"),
                     (("ingest.example.com", "/mac/api/v1/write", "POST"), "400"),
                     (("ingest.example.com", "/mac/api/v1/write", "POST", "dev"), "403"),
                     (("ingest.example.com", "/mac/api/v1/write", "POST", "mac"), "204"),
                     (("ingest.example.com", "/mac/api/v1/write", "GET", "mac"), "403"),
                     (("ingest.example.com", "/dev/api/v1/write", "POST", "mac"), "403"),
                     (("grafana.example.com", "/", "GET", None, True), "301")]
            for arguments, expected in cases:
                actual = status(*arguments)
                assert actual == expected, (arguments, actual, expected)
                print("PASS", arguments[:3], actual)
        finally:
            subprocess.run(["docker", "rm", "-f", name], capture_output=True)


if __name__ == "__main__":
    main()
