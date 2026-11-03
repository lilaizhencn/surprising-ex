# Legal Content and Public Media Deployment

The content package covers user terms, privacy, cookies, trading risk, derivatives risk, funds/withdrawals, fees, identity/compliance, complaints, and security. The configured operator/contact follow the project's existing business setup; do not substitute another exchange's operator or licensing claims. A translation is not evidence of service eligibility or authorization in a jurisdiction.

`source/` retains complete language-specific bodies. Review corresponding sections and financial/privacy meaning before publication. Published CMS revisions are immutable. Operational eligibility and applicable restrictions require separate review before enabling a service; user acceptance does not override restrictions. This guide describes deployment behavior, not a new legal assessment.

## Release order

1. Apply `20261031-content-media.sql`, `20261031-legal-documents.sql`, then `20261031-content-media-references.sql`. New `init.sql` already includes the structures.
2. Start the updated Gateway CMS. Administrator access remains distinct from ordinary-user consent requirements.
3. Validate the content bundle offline: `python3 deployment/legal/import-drafts.py`.
4. Supply `LEGAL_ADMIN_TOKEN` through the environment, then run `python3 deployment/legal/import-drafts.py --url https://api.example.com --apply`. Never put tokens in source, chat, commits, or command arguments. The importer creates drafts, not published revisions, and uses stable digest-based revision identities.
5. Preview all twelve languages, review content and reason, and publish through the approval workflow. Both required user terms and privacy revisions must be published before ordinary registration/login is opened.
6. Web/mobile load current revisions and actual reading language. Confirmation is opt-in and records `X-Legal-Acceptance: revisionId:language,...` during registration/login/MFA/provider login/session refresh. New revisions require renewed confirmation. Preserve older documents and acceptance times; reject stale revision identities.

Unpublished required content returns 503. Published but unaccepted current revisions return 428 `LEGAL_CONSENT_REQUIRED`. Administrative publication approval may also require a precondition, but that is a separate workflow. Privacy acknowledgement does not mean optional marketing or arbitrary cross-border consent.

## Dedicated public R2 storage

Use bucket `surprising-ex-public`, CDN `https://ex-cdn.tokdou.com`, and prefix `public/exchange/content/`. Private KYC/C2C attachments must not move into public storage. The recorded setup uses default jurisdiction/APAC, Standard storage, TLS 1.2 minimum, and disabled r2.dev; verify live infrastructure before changing it.

[The CORS policy](../content-r2.cors.json) permits configured exchange Web/API origins for GET/HEAD and media Range access. Use a dedicated bucket-scoped object read/write key, not Social/private attachment credentials or a CLI OAuth token.

Real credentials are outside the repository in `~/.config/surprising-ex/content-r2.env` with mode 0600. Inject `GATEWAY_CONTENT_R2_ACCESS_KEY_ID` and `GATEWAY_CONTENT_R2_SECRET_ACCESS_KEY`; the external file also supplies endpoint/enablement. Bucket/CDN settings remain in application configuration. See [the empty example](../content-r2.env.example).

## References and cleanup

Saving an article retains media references in the same transaction. Historical documents retain their references. A separate bounded cleanup worker removes at most twenty uploads per hourly pass only after twenty-four hours without a reference. Row locks/foreign keys protect concurrent saves. Failed deletion remains retryable; cleanup must not scan other buckets.

Deploy compatible Gateway, admin, Web, and mobile CDN/consent behavior. Test draft import, approval, publication, login/refresh confirmation, reference retention, and private/public access boundaries in staging before switching ordinary traffic. Storage creation or backend compilation is not proof of completed production rollout.

## Architecture reference

See the [project architecture and class-level trading flows](../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
