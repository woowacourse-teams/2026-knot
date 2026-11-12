# Cloudflare Origin CA root

`cloudflare-origin-rsa-root.pem` is the public Cloudflare Origin RSA root CA certificate downloaded from the official Cloudflare documentation link:

- https://developers.cloudflare.com/ssl/static/origin_ca_rsa_root.pem
- SHA-256 fingerprint: `D3:C7:E8:5C:91:70:7F:C0:A1:2A:BC:5D:88:26:67:47:AA:4F:A8:E7:B1:62:F6:33:FF:B3:C9:D9:89:94:76:20`
- Valid until: 2029-08-15 17:00:00 UTC

The production deployment hook uses this root only when it verifies the local HTTPS Health Check through Nginx. TLS certificate and hostname verification remain enabled. Dev continues to use the system trust store.

When rotating the origin certificate, confirm its issuer and hostname against the replacement chain, update this root only from Cloudflare's official documentation, and record the new fingerprint and validity period here.
