# Security model

## Protected data

- Admin JWTs, API keys, and encrypted playground transcripts are stored with AES-GCM keys generated in Android Keystore.
- Passwords are used only for `admin/auth/signin` and are never persisted.
- Android backup/data transfer is disabled, so app-private credentials are not exported through platform backup.
- Backup files selected for server restore may themselves contain secrets. They are bounded to 50 MiB, processed in memory, never logged, and discarded when the restore UI closes.

## Network boundaries

- HTTPS is required by default. HTTP must be explicitly enabled per instance for a trusted network.
- Base URL path prefixes are retained for every endpoint.
- URLs containing credentials, queries, or fragments are rejected.
- Redirects and cookies are disabled in the HTTP client to prevent bearer-token forwarding across origins.
- OAuth calls use a fixed provider/action allowlist. Provider authorization links are opened only when they are HTTPS URLs without embedded credentials.
- `X-Project-ID` is added only to scoped admin calls. API-key protocol requests reject admin-only project/channel routing parameters.

## State and mutation safety

- Cached snapshots are separated by instance and project. Private transcripts are separated by instance, project, and token fingerprint.
- Instance/project changes cancel refreshes and invalidate generation fences; stale reads and mutations cannot publish results to a new target.
- Destructive operations require explicit confirmation. Mutations use exact response checks and readback where the server exposes sufficient state.
- Secret fields reject masked placeholders and are excluded from generic displays. Existing channel credentials require an explicit in-session authorization step before replacement.
- Error messages classify transport, HTTP, authentication, permission, and validation failures without echoing untrusted server prose.

## Limitations and reporting

- A compromised device with an unlocked app session can perform actions allowed by the current AxonHub credential. Android Keystore protects data at rest, not an already authorized UI session.
- The application relies on the platform trust store and does not pin a specific AxonHub certificate.
- Broad restore has no server transaction revision for complete cross-entity readback; the UI reports this limitation explicitly.

Report suspected vulnerabilities privately to the repository owner. Do not include production credentials, tokens, or backup contents in an issue or log bundle.
