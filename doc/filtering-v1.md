# V1 filtering authority

Loki reads `urlsRedefining.filtering` from the authentication server metadata
and configures the V1 MSAL authority automatically. Kintare derives this URL
and `filteringAuthentication` (the certificate-authenticated token endpoint)
from `FILTERING_URL`. The normal `text-filtering-config` still supplies the
server's client ID, PKCS#12 certificate, scope and tenant (`kintare`).

For a provider without this metadata, the explicit
`-DLoki.filteringV1.authority=https://filtering.example.com/` remains available.
With neither configuration, Loki leaves Minecraft's V1 MSAL flow unchanged.
The authority must be an HTTPS origin.

Loki changes only the MSAL authority from Microsoft's host to this host and
disables Microsoft instance discovery for that custom authority. Minecraft
continues to load the PKCS#12 certificate, acquire and cache the token through
MSAL, and set the filtering request's Authorization header. The tenant ID from
`text-filtering-config` becomes the first path segment; MSAL posts to
`/{tenantId}/oauth2/v2.0/token`.
