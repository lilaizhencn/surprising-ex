"""最后执行：风控停用不改变原订单权益，全部结清后退押并核对真实 Core 期末资金。"""
import json
from decimal import Decimal
from pathlib import Path
import runpy

qa = runpy.run_path(str(Path(__file__).with_name('c2c-fused-live.py')), run_name='c2c_live_library')
req, wait, fixture = qa['req'], qa['await_value'], qa['fixture']
seller = fixture['users']['seller']['user']['userId']
assert not fixture.get('businessFinishStarted'), 'Inspect the original finalization before continuing'
fixture['businessFinishStarted'] = True
qa['save']()
ads = req('GET', '/api/v1/c2c/me/ads', 'seller')['items']
ad = next(row for row in ads if row['side'] == 'SELL' and row['status'] == 'ACTIVE' and Decimal(row['availableQuantity']) >= 1)
order = qa['create'](ad['adId'], 'buyer', '1')
identity = order['orderId']
req('POST', f'/api/v1/c2c/orders/{identity}/paid', 'buyer', {'proofId': None})
merchant = req('GET', '/api/v1/c2c/me', 'seller')['merchant']
qa['approved']('POST', f'/api/v1/admin/c2c/merchants/{seller}/review', {
    'revision': merchant['revision'], 'decision': 'SUSPEND', 'tier': 'VERIFIED', 'reason': 'Local risk suspension with an existing paid order',
})
bond = req('GET', '/api/v1/c2c/me/merchant-bond', 'seller')['bond']
req('POST', f"/api/v1/c2c/me/merchant-bonds/{bond['bondId']}/withdraw", 'seller', expected=(409,))
req('POST', '/api/v1/c2c/quotes', 'buyer', {
    'adId': ad['adId'], 'amountType': 'CRYPTO', 'amount': '1', 'paymentAccountId': fixture['sellerAccount'],
}, expected=(409,))
assert all(row['status'] != 'ACTIVE' for row in req('GET', '/api/v1/c2c/me/ads', 'seller')['items'])
qa['release'](identity, 'seller')
qa['reconcile']('suspended-merchant-paid-order-still-settles')
merchant = req('GET', '/api/v1/c2c/me', 'seller')['merchant']
qa['approved']('POST', f'/api/v1/admin/c2c/merchants/{seller}/review', {
    'revision': merchant['revision'], 'decision': 'APPROVE', 'tier': 'VERIFIED', 'reason': 'Local risk review completed before ordinary original-owner withdrawal',
})
qa['finish_funds']()
held = req('GET', '/api/v1/c2c/me', 'seller')['escrow']
assert sum(int(row['confirmedEscrowUnits']) for row in held) == int(bond['amountUnits'])
req('POST', f"/api/v1/c2c/me/merchant-bonds/{bond['bondId']}/withdraw", 'seller')
returned = wait(lambda: req('GET', '/api/v1/c2c/me/merchant-bond', 'seller'),
                lambda row: row['bond']['status'] == 'RETURNED', 'Final original seller bond refund', seconds=60)
assert returned['bond']['bondId'] == bond['bondId'] and returned['bond']['amountUnits'] == bond['amountUnits']
qa['reconcile']('all-ads-orders-and-merchant-bonds-returned')
assert all(int(row['confirmedEscrowUnits']) == 0 for row in req('GET', '/api/v1/c2c/me', 'seller')['escrow'])
assert qa['sql']("SELECT count(*) FROM c2c_merchant_bonds WHERE status NOT IN ('RETURNED','FAILED')") == '0'
settings = req('GET', '/api/v1/admin/gateway/market-maker/business-settings?productLine=SPOT', 'admin')
business = settings['settings']
business['engine']['enabled'] = False
req('POST', '/api/v1/admin/gateway/market-maker/business-settings?productLine=SPOT', 'admin', {
    'settings': business, 'expectedVersion': settings['version'], 'reason': 'Close only the owned local QA maker quotes after financial verification',
})
accounts = wait(qa['balances'], lambda rows: all(int(r['lockedUnits']) == 0 for rs in rows.values() for r in rs),
                'Owned maker orders canceled and all frozen funds returned', seconds=60)
totals = qa['totals'](accounts)
assert totals == fixture['initialTotals']
reconciliation = wait(lambda: req('GET', '/api/v1/admin/c2c/reconciliation', 'admin'),
                      lambda row: row['status'] == 'MATCHED', 'Final escrow and fees reconciliation')
evidence = {
    'passed': True, 'scope': 'Local SPOT Core, real JWT, PostgreSQL, realtime and maker; synthetic KYC, OTP and off-platform fiat',
    'suspensionBlocksNewOrdersAndWithdrawal': True, 'existingPaidOrderCompleted': identity,
    'merchantBondOriginalOwnerAndAmountReturned': True, 'allC2cObjectsTerminal': True,
    'merchantEscrowSummaryAfterRefund': '0', 'allLockedUnits': '0',
    'initialTotals': fixture['initialTotals'], 'finalTotals': totals,
    'fundsDifference': {asset: 0 for asset in totals}, 'accounts': accounts, 'reconciliation': reconciliation,
}
(qa['ROOT'] / 'business-final-funds.json').write_text(json.dumps(evidence, indent=2))
fixture['businessFinishPassed'] = True
qa['save']()
print('PASS final suspension, original bond withdrawal, escrow summary, terminal objects and total funds; all locked units zero', flush=True)
