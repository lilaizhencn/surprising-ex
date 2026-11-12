# C2C Business and Acceptance Boundaries

C2C runs inside Gateway and reuses authentication, KYC, exact asset units, funds commands, approval, and audit. It does not add a product line or an independent SQL wallet. Web, mobile, and administration consume the same REST contracts.

## Business flow

1. Market lookup filters real advertisements by side, asset, fiat, amount, payment method, merchant status, limits, and ordering. Quick trading uses the same quote eligibility path.
2. Approved merchants publish advertisements. Sell inventory is backed by a completed transfer to escrow before listing. Buy advertisements escrow the seller's assets when matched. Closing an advertisement does not invalidate active orders.
3. Short-lived quotes freeze price, quantity, fee, limits, and payment conditions. Order creation uses quote ID and an idempotency key; SQL row locks allocate advertisement inventory. Reject expired quotes, self-trading, blocked/restricted users, and invalid limits.
4. Payment details are immutable order snapshots visible only to authorized participants. Buyer payment confirmation does not release escrow. Seller verification and protected release initiate the actual funds transfer.
5. Cancellation/expiry before payment can restore inventory or refund. Paid orders must not auto-refund merely due to timeout. Appeals require protected evidence access and independent administrative approval; fixed funds intent cannot later be redirected.
6. Messages, relationships, payment details, evidence, and reports are scoped to the owner, participants, or authorized support. Statistics come from actual terminal orders.

## Financial invariants

Funding and spot currently share SPOT Core balance; do not count it twice. Reserved escrow IDs come from the existing user sequence but do not create login users. Public cross-user transfer rejects internal destinations without a real receiving user.

Use `ProductAccountClient.transferOut/transferIn/completeTransfer`. Never treat an unfinished transfer directly to the buyer as a freeze: the transfer reconciler can complete it. Every advertisement/order escrow, release, fee, and refund retains persisted immutable funds intent and a stable transfer ID. Unknown results resume the original identity and become business-terminal only after Core confirmation.

Reconcile SQL inventory, open orders, pending refunds/releases/transfers, and actual escrow balance. Asset quantities use int64 smallest units. Quotes, fiat values, and fees use exact decimals; public int64 identifiers, units, versions, and event IDs are decimal strings. Do not convert them to floating point.

## Initialization and security

`POST /api/v1/admin/c2c/initialization` accepts no arbitrary account, amount, or asset body. `C2cAdminInitialization` applies existing approval/audit before stable zero-amount Core adjustments initialize fixed escrow/fee accounts. It creates no funds or login user and does not open the market. An older Core rejecting zero initialization keeps initialization rejected and trading closed.

Reconciliation must actually read both accounts and match committed intent. UNAVAILABLE, PENDING, and MISMATCH are not zero balances or permission to open trading. `surprising.c2c.enabled` defaults off; the deployment switch disables transaction endpoints/tasks. The database business switch stops new trading while existing orders can still settle.

`AdminPermissionFilter` and services enforce ordinary, sensitive-data, and funds-decision permissions. Failed audit must not disclose payment plaintext. Approval binds the complete action; operator and approver cannot participate in the affected trade. Funds destinations and amounts come from the original business state.

External bank/payment transfers are not automatically verified by screenshots or a buyer assertion. KYC admission does not establish a verified legal-name match when the shared contract lacks that field. Payment accounts require their own review; later edits cannot change an active order's approved payment snapshot.

## Delivery scope and evidence

The initial 2026-10-20 scope covered exact quoting, funds intent/escrow, order/payment/release/cancel/timeout/appeal backend paths, PostgreSQL concurrency/security/unknown outcomes, and real SPOT funds checks. It did not establish all merchant/payment, message/evidence, advanced market, relationship, alert, or three-client UI flows. Two existing protocol fingerprint vector failures were reproduced on the earlier protocol source and were not changed to manufacture passing results.

The later 2026-10-21 work added actual merchant staking, original-amount unstaking, and primary three-client trading operations with local Core validation. Do not interpret the earlier checklist as the latest delivery state, or expand the later scope to every advanced page.

Chat, private attachments, temporary R2 access, and support audit reuse exchange identity and `/ws/v1`. Backend verification is distinct from complete three-client interaction acceptance.

The earlier foundation, chat, and merchant-acceptance documents are not present in this checkout. Consult Git history for those recorded runs; do not interpret the historical scope above as current end-to-end acceptance.

`scripts/c2c-fused-live.py` is a stateful local QA tool with explicit run ownership, dedicated database identity, and real Gateway PID/port checks. Do not bypass duplicate-action protections by changing fixtures after funds operations start. Retain original credentials and query original operations after connection/restart failures. Real payment data, temporary databases, screenshots, APKs, and runtime clusters are not source artifacts.

## Architecture reference

See the [project architecture and class-level trading flows](../../../../../../../../README.md). Historical validation notes describe their recorded versions, not guarantees for the current implementation.
