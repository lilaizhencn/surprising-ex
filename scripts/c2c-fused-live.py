"""隔离 SPOT FUSED 实链路验收；仅用 SQL 准备测试身份、角色及显式合成 KYC。

必须提供 C2C_LIVE_QA_ROOT 与 C2C_LIVE_DB_USER，并已准备本轮私有 runtime.py。
金额、成交和划转均经真实 API/Core；不接触已部署服务器或手机账号。
"""
import hashlib, http.client, json, os, pathlib, signal, subprocess, sys, time, urllib.error, urllib.parse, urllib.request, uuid
import re
ROOT = pathlib.Path(os.environ['C2C_LIVE_QA_ROOT']).resolve()
if not re.fullmatch('/(?:private/)?tmp/surprising-c2c-\\d{8}_\\d{6}/live', str(ROOT)):
    raise RuntimeError('C2C_LIVE_QA_ROOT must identify an owned isolated C2C runtime')
owner = json.loads((ROOT.parent / 'owner.json').read_text())
if owner.get('purpose') != 'C2C authenticated HTTP, settlement lifecycle, security and admin integration':
    raise RuntimeError('The runtime owner marker does not identify C2C QA')
DB = owner['live_database']
if not re.fullmatch('surprising_c2c_\\d{8}_\\d{6}', DB):
    raise RuntimeError('Only a dedicated surprising_c2c timestamped database is permitted')
DB_USER = os.environ['C2C_LIVE_DB_USER']
processes = json.loads((ROOT / 'processes.json').read_text())
gateway_pid = processes['gateway']['pid']
gateway_command = subprocess.check_output(['ps', '-p', str(gateway_pid), '-o', 'command='], text=True)
immutable_gateway = ROOT / 'artifacts' / 'gateway.jar'
owned_immutable = str(immutable_gateway) in gateway_command and immutable_gateway.is_file()
if owned_immutable:
    proof = json.loads((ROOT / 'verified-artifact.json').read_text())
    if hashlib.sha256(immutable_gateway.read_bytes()).hexdigest() != proof['sha256']:
        raise RuntimeError('The owned immutable Gateway does not match its build evidence')
if str(ROOT) not in gateway_command or not ('surprising-gateway' in gateway_command or owned_immutable):
    raise RuntimeError('The local Gateway is not owned by the named QA runtime')
listeners = subprocess.check_output(['lsof', '-nP', '-t', '-iTCP:9094', '-sTCP:LISTEN'], text=True).split()
if str(gateway_pid) not in listeners:
    raise RuntimeError('Port 9094 does not belong to the named QA Gateway')
BASE = 'http://127.0.0.1:9094'
fixture_path = ROOT / 'private-fixture.json'
fixture = json.loads(fixture_path.read_text()) if fixture_path.exists() else {'users': {}}
events = []

def save():
    fixture_path.write_text(json.dumps(fixture, indent=2))
    fixture_path.chmod(384)

def sql(query):
    return subprocess.check_output(['psql', '-v', 'ON_ERROR_STOP=1', '-h', '127.0.0.1', '-U', DB_USER, '-d', DB, '-Atqc', query], text=True).strip()

def req(method, path, user=None, body=None, headers=None, expected=(200,), raw=None):
    data = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
    h = {'Content-Type': 'application/json', 'X-Product-Line': 'SPOT'}
    if user:
        h['Authorization'] = 'Bearer ' + fixture['users'][user]['accessToken']
    if headers:
        h.update(headers)
    request = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(request, timeout=35)
    except urllib.error.HTTPError as failure:
        response = failure
    content = response.read()
    try:
        result = json.loads(content) if content else {}
    except Exception:
        result = {'nonJson': content[:600].decode(errors='replace')}
    events.append({'method': method, 'path': path, 'status': response.status, 'millis': round((time.monotonic() - started) * 1000, 1)})
    (ROOT / 'http-evidence.json').write_text(json.dumps(events, indent=2))
    assert response.status in expected, (method, path, response.status, result)
    return result

def approved(method, path, body, service='c2c'):
    raw = None if body is None else json.dumps(body, ensure_ascii=False, separators=(',', ':')).encode()
    parsed = urllib.parse.urlsplit(path)
    approval = req('POST', '/api/v1/admin/approvals', 'admin', {'service': service, 'httpMethod': method, 'requestPath': parsed.path, 'queryString': parsed.query or None, 'requestBodySha256': None if raw is None else hashlib.sha256(raw).hexdigest(), 'reason': 'Isolated C2C real Core QA review'})
    identity = approval['approvalId']
    req('POST', f'/api/v1/admin/approvals/{identity}/approve', 'reviewer', {'reason': 'Independent synthetic QA evidence checked'})
    return req(method, path, 'admin', headers={'X-Admin-Approval-Id': str(identity)}, raw=raw)

def challenge(user, scene='WITHDRAWAL'):
    req('POST', '/api/v1/security/verification/challenge', user, {'sceneCode': scene})
    return {'emailCode': '123456', 'totpCode': ''}

def await_value(read, condition, description, seconds=40):
    deadline = time.monotonic() + seconds
    while True:
        value = read()
        if condition(value):
            return value
        assert time.monotonic() < deadline, (description, value)
        time.sleep(0.25)

def bootstrap_users():
    for role in ('admin', 'reviewer', 'seller', 'buyer', 'other', 'maker'):
        if role in fixture['users']:
            continue
        name = 'qa_c2c_' + role
        password = 'C2C-Isolated-20261020-' + role + '!'
        result = req('POST', '/api/v1/auth/register', body={'email': name + '@surprising.test', 'password': password})
        fixture['users'][role] = result | {'password': password, 'name': name}
        save()
    for role in ('admin', 'reviewer'):
        actor = fixture['users'][role]
        uid = int(actor['user']['userId'])
        sql(f"INSERT INTO gateway_user_roles(user_id,role_id) SELECT {uid},role_id FROM gateway_roles WHERE role_code='ADMIN' ON CONFLICT DO NOTHING")
        actor.update(req('POST', '/api/v1/auth/login', body={'identifier': actor['name'] + '@surprising.test', 'password': actor['password']}))
        save()
    for role in ('seller', 'buyer', 'other', 'maker'):
        uid = int(fixture['users'][role]['user']['userId'])
        sql(f"INSERT INTO gateway_user_kyc_profiles(user_id,kyc_level,status,country,provider) VALUES({uid},'STANDARD','VERIFIED','CN','MANUAL') ON CONFLICT(user_id) DO NOTHING")
    print('Synthetic users ready; administrators use ADMIN, not super-admin', flush=True)

def setup_market():
    await_value(lambda: req('GET', '/api/v1/c2c/status'), lambda row: row.get('spotReady') is True, 'SPOT gateway activation')
    configuration = req('GET', '/api/v1/admin/c2c/configuration', 'admin')
    if not configuration['markets']:
        market = approved('POST', '/api/v1/admin/c2c/markets', {'marketId': None, 'revision': '0', 'asset': 'USDT', 'fiat': 'CNY', 'fiatDecimals': 2, 'enabled': True, 'minFiat': '1', 'maxFiat': '100000', 'minPrice': '1', 'maxPrice': '100', 'buyerFeePpm': 1000, 'paymentWindowSeconds': 60, 'maxOpenOrders': 10, 'requireKyc': True, 'vipMinCompleted': 0, 'reason': 'Isolated synthetic CNY market'})
        fixture['marketId'] = market['marketId']
        save()
    else:
        fixture['marketId'] = configuration['markets'][0]['marketId']
        save()
    for method in configuration['paymentMethods']:
        if method['code'] != 'ALIPAY':
            continue
        if not method['enabled'] or 'CNY' not in method['currencies']:
            approved('POST', '/api/v1/admin/c2c/payment-methods', {'code': 'ALIPAY', 'revision': method['revision'], 'label': 'QA Payment', 'enabled': True, 'currencies': ['CNY'], 'requiresBankName': False, 'supportsQr': True, 'reason': 'Synthetic payment method setup'})
    for role in ('seller', 'buyer', 'other'):
        if role + 'Account' not in fixture:
            account = req('POST', '/api/v1/c2c/payment-accounts', role, {'accountId': None, 'revision': '0', 'fiat': 'CNY', 'methodCode': 'ALIPAY', 'details': {'holderName': 'QA ' + role, 'accountNumber': 'qa-synthetic-' + role, 'bankName': '', 'qrAttachmentId': None}, 'verification': challenge(role, 'SECURITY_SETTINGS')})
            fixture[role + 'Account'] = account['accountId']
            save()
        account = req('GET', '/api/v1/c2c/payment-accounts/' + fixture[role + 'Account'], role)
        if account['status'] != 'APPROVED':
            approved('POST', '/api/v1/admin/c2c/payment-accounts/' + fixture[role + 'Account'] + '/review', {'revision': account['revision'], 'decision': 'APPROVE', 'identityChecked': True, 'reason': 'Synthetic identities manually checked in QA fixture'})
    if 'merchant' not in fixture:
        profile = req('GET', '/api/v1/c2c/me', 'seller')
        application = req('POST', '/api/v1/c2c/me/merchant-application', 'seller', {'revision': '0', 'application': 'Synthetic merchant for isolated Core QA'})
        fixture['merchant'] = application
        save()
    policy = req('GET', '/api/v1/admin/c2c/merchant-bond-policy', 'admin')
    if not policy['configured']:
        policy = approved('PUT', '/api/v1/admin/c2c/merchant-bond-policy', {'revision': '0', 'asset': 'USDT', 'amount': '100', 'reason': 'Isolated QA merchant guarantee rule; never a deployment default'})
    guarantee = req('GET', '/api/v1/c2c/me/merchant-bond', 'seller')
    if guarantee['bond'] is None or guarantee['bond']['status'] == 'RETURNED':
        uid = fixture['users']['seller']['user']['userId']
        account = await_value(lambda: req('GET', f'/api/v1/admin/gateway/account/balances?userId={uid}', 'admin', expected=(200, 503)), lambda row: 'balances' in row, 'Merchant realtime funding')
        available = sum(int(row['availableUnits']) for row in account['balances'] if row['asset'] == policy['asset'])
        assert available >= int(policy['amountUnits']), 'Run the trading funding phase before market setup; no bond command sent'
        fixture['merchantBondRequestId'] = str(uuid.uuid4())
        save()
        req('POST', '/api/v1/c2c/me/merchant-bond', 'seller', {'requestId': fixture['merchantBondRequestId'], 'policyRevision': policy['revision'], 'verification': challenge('seller')})
    guarantee = await_value(lambda: req('GET', '/api/v1/c2c/me/merchant-bond', 'seller'), lambda row: row['bond'] is not None and row['bond']['status'] in ('LOCKED', 'FAILED'), 'Merchant real Core guarantee confirmation')
    assert guarantee['bond']['status'] == 'LOCKED', 'Original guarantee debit rejected; inspect and fund the synthetic account before a new request'
    uid = fixture['users']['seller']['user']['userId']
    merchants = req('GET', '/api/v1/admin/c2c/merchants?userId=' + str(uid), 'admin')['items']
    if merchants[0]['status'] != 'APPROVED':
        approved('POST', f'/api/v1/admin/c2c/merchants/{uid}/review', {'revision': merchants[0]['revision'], 'decision': 'APPROVE', 'tier': 'VERIFIED', 'reason': 'Isolated merchant fixture approved'})
    configuration = req('GET', '/api/v1/admin/c2c/configuration', 'admin')
    if not configuration['enabled']:
        reconciliation = req('GET', '/api/v1/admin/c2c/reconciliation', 'admin')
        if reconciliation['status'] == 'UNAVAILABLE':
            initialized = approved('POST', '/api/v1/admin/c2c/initialization', None)
            assert initialized['commandsApplied'], initialized
            reconciliation = await_value(lambda: req('GET', '/api/v1/admin/c2c/reconciliation', 'admin'), lambda row: row['status'] == 'MATCHED', 'new internal account realtime synchronization')
        assert reconciliation['status'] == 'MATCHED', reconciliation
        approved('PUT', '/api/v1/admin/c2c/configuration', {'revision': configuration['revision'], 'enabled': True, 'maxActiveAds': 20, 'reason': 'Open isolated QA market after cash reconciliation'})
    print('C2C configuration and manually reviewed accounts ready', flush=True)

def setup_trading():
    if 'instrument' not in fixture:
        draft = req('GET', '/internal/v1/instruments/snapshot?productLine=LINEAR_PERPETUAL')['instruments'][0]
        for name in ('changeId', 'lastChangeId', 'createdAt', 'updatedAt', 'baseAsset', 'quoteAsset', 'settleAsset', 'contractValueAsset'):
            draft.pop(name, None)
        draft.update({'instrumentId': None, 'symbol': 'QA-C2C-BTC-USDT', 'instrumentType': 'SPOT', 'contractType': 'SPOT', 'status': 'DRAFT', 'priceTickUnits': 10000000, 'quantityStepUnits': 100000, 'notionalMultiplierUnits': 10000, 'minNotionalUnits': 1, 'minQuantitySteps': 1, 'reduceOnlyEnabled': False, 'riskLimitBrackets': [], 'fundingIntervalHours': 0, 'interestRatePpm': 0, 'fundingRateCapPpm': 0, 'fundingRateFloorPpm': 0, 'minValidIndexSources': 1})
        result = req('POST', '/api/v1/admin/gateway/instrument-admin/upsert?reason=c2c-qa&expectedChangeId=0', 'admin', draft)
        fixture['instrument'] = result
        save()
    for status in ('PRE_TRADING', 'TRADING'):
        current = fixture['instrument']
        if current['status'] == 'TRADING' or (current['status'] == 'PRE_TRADING' and status == 'PRE_TRADING'):
            continue
        path = f"/api/v1/admin/gateway/instrument-admin/{current['instrumentId']}/status?productLine=SPOT&status={status}&reason=c2c-qa&expectedChangeId={current['lastChangeId']}"
        fixture['instrument'] = req('POST', path, 'admin')
        save()
    instrument = fixture['instrument']['instrumentId']
    await_value(lambda: req('GET', f'/api/v1/admin/gateway/trading-orders/instrument-sync/{instrument}?productLine=SPOT', 'admin', expected=(200, 400)), lambda r: r.get('state') == 'APPLIED', 'Core instrument synchronization')
    for role, assets in {'seller': {'USDT': 100000000000}, 'buyer': {'USDT': 100000000000}, 'other': {'USDT': 100000000000}, 'maker': {'USDT': 100000000000000, 'BTC': 1000000000}}.items():
        for asset, units in assets.items():
            key = 'funded-' + role + '-' + asset
            if fixture.get(key):
                continue
            uid = int(fixture['users'][role]['user']['userId'])
            result = approved('POST', '/api/v1/admin/gateway/account/balance-adjustments', {'userId': uid, 'asset': asset, 'amountUnits': units, 'referenceId': 'c2c-qa-deposit-' + role + '-' + asset, 'reason': 'Isolated C2C real Core QA funding'}, 'account')
            assert int(result['availableUnits']) == units, result
            fixture[key] = True
            save()
    print('SPOT instrument and real Core test balances ready', flush=True)
    settings = req('GET', '/api/v1/admin/gateway/market-maker/business-settings?productLine=SPOT', 'admin')
    business = settings['settings']
    business['engine']['enabled'] = True
    business['trade']['enabled'] = False
    business['referenceMarket']['enabled'] = False
    req('POST', '/api/v1/admin/gateway/market-maker/business-settings?productLine=SPOT', 'admin', {'settings': business, 'expectedVersion': settings['version'], 'reason': 'Enable isolated local-reference maker during C2C QA'})
    maker_pid = json.loads((ROOT / 'processes.json').read_text())['maker']['pid']
    if fixture.get('makerProcess') != maker_pid:
        fixture['makerStrategy'] = False
        fixture['makerProcess'] = maker_pid
        save()
    if not fixture.get('makerStrategy'):
        result = req('POST', '/api/v1/admin/gateway/market-maker/strategy-definitions?reason=c2c-qa', 'admin', {'strategyId': 'qa-c2c-20261020', 'productLine': 'SPOT', 'enabled': True, 'accountIds': [int(fixture['users']['maker']['user']['userId'])], 'instrumentIds': [str(instrument)], 'baseQuantitySteps': 1, 'marginMode': 'CROSS', 'spreadTicks': 20, 'levelSpacingTicks': 10, 'maxInventorySteps': 1000000, 'maxInventorySkewPpm': 0, 'orderLevels': 2, 'initialAnchorPriceTicks': 500000, 'version': 0})
        fixture['makerStrategy'] = True
        save()
    await_value(lambda: req('GET', f'/api/v1/gateway/trading-market/orderbook?instrumentId={instrument}&depth=5', 'maker', expected=(200, 503)), lambda r: bool(r.get('bids')) and bool(r.get('asks')), 'Real maker quote readiness', seconds=60)
    print('Real SPOT maker quoting while C2C cash scenarios run', flush=True)

def own_ad(identity):
    return next((row for row in req('GET', '/api/v1/c2c/me/ads', 'seller')['items'] if row['adId'] == identity))

def publish(side, quantity, wait=True):
    payload = {'marketId': fixture['marketId'], 'side': side, 'price': '6.65', 'quantity': quantity, 'minFiat': '1', 'maxFiat': '100000', 'methodCodes': ['ALIPAY'], 'paymentAccountIds': [fixture['sellerAccount']] if side == 'SELL' else [], 'paymentWindowSeconds': 60, 'terms': 'Synthetic Core funds verification', 'minCompleted': 0}
    key = 'qa-live-ad-' + uuid.uuid4().hex
    identity = {'key': key, 'kind': 'AD_PUBLISH', 'hash': hashlib.sha256(json.dumps(payload, separators=(',', ':')).encode()).hexdigest(), 'payload': payload}
    fixture.setdefault('submissions', []).append(identity)
    save()
    result = req('POST', '/api/v1/c2c/ads', 'seller', {'requestKey': key, 'payload': payload, 'verification': challenge('seller')})
    assert result['status'] == 'SUCCEEDED', result
    identity['resultId'] = result['resultId']
    save()
    if wait:
        await_value(lambda: own_ad(identity['resultId']), lambda row: row['status'] == 'ACTIVE', 'Ad funded and activated')
    return identity['resultId']

def create(ad, user, quantity, lost_reply=False):
    offer = own_ad(ad)
    account = fixture['sellerAccount'] if offer['side'] == 'SELL' else fixture[user + 'Account']
    quote = req('POST', '/api/v1/c2c/quotes', user, {'adId': ad, 'amountType': 'CRYPTO', 'amount': quantity, 'paymentAccountId': account})
    key = 'qa-live-order-' + uuid.uuid4().hex
    identity = {'key': key, 'kind': 'ORDER_CREATE', 'hash': hashlib.sha256(quote['quoteId'].encode()).hexdigest(), 'quoteId': quote['quoteId'], 'user': user}
    fixture.setdefault('submissions', []).append(identity)
    save()
    command = {'requestKey': key, 'quoteId': quote['quoteId'], 'verification': challenge(user) if offer['side'] == 'BUY' else None}
    if lost_reply:
        connection = http.client.HTTPConnection('127.0.0.1', 9094, timeout=10)
        connection.request('POST', '/api/v1/c2c/orders', body=json.dumps(command, separators=(',', ':')), headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + fixture['users'][user]['accessToken']})
        connection.close()
        result = await_value(lambda: req('GET', f"/api/v1/c2c/submissions/{key}?kind=ORDER_CREATE&requestHash={identity['hash']}", user), lambda row: row['status'] in ('SUCCEEDED', 'REJECTED', 'STOPPED'), 'GET-only lost reply recovery')
    else:
        result = req('POST', '/api/v1/c2c/orders', user, command)
    assert result['status'] == 'SUCCEEDED', result
    identity['resultId'] = result['resultId']
    save()
    return await_value(lambda: req('GET', '/api/v1/c2c/orders/' + identity['resultId'], user), lambda row: row['status'] == 'PAYMENT_PENDING', 'Order escrow applied')

def status(order, user, expected):
    return await_value(lambda: req('GET', '/api/v1/c2c/orders/' + order, user), lambda row: row['status'] == expected, 'Order ' + expected)

def balances():
    ids = [int(fixture['users'][role]['user']['userId']) for role in ('seller', 'buyer', 'other', 'maker')]
    ids += list(map(int, sql('SELECT escrow_user_id,fee_user_id FROM c2c_configuration').split('|')))
    result = {}
    for uid in ids:
        response = await_value(lambda: req('GET', f'/api/v1/admin/gateway/account/balances?userId={uid}', 'admin', expected=(200, 503)), lambda row: 'balances' in row, 'Realtime balance for ' + str(uid))
        result[str(uid)] = response['balances']
    return result

def totals(accounts):
    result = {}
    for rows in accounts.values():
        for row in rows:
            result[row['asset']] = result.get(row['asset'], 0) + int(row['availableUnits']) + int(row['lockedUnits'])
    return result

def reconcile(stage):
    value = await_value(lambda: req('GET', '/api/v1/admin/c2c/reconciliation', 'admin'), lambda row: row['status'] == 'MATCHED', 'C2C cash reconciliation ' + stage)
    accounts = balances()
    combined = totals(accounts)
    assert combined == fixture['initialTotals'], (stage, combined, fixture['initialTotals'])
    instrument = fixture['instrument']['instrumentId']
    book = req('GET', f'/api/v1/gateway/trading-market/orderbook?instrumentId={instrument}&depth=2', 'maker')
    assert book['bids'] and book['asks'], ('maker stopped quoting', book)
    evidence = {'stage': stage, 'reconciliation': value, 'accounts': accounts, 'totals': combined, 'fundsDifference': {asset: 0 for asset in combined}, 'makerBidLevels': len(book['bids']), 'makerAskLevels': len(book['asks'])}
    with (ROOT / 'funds-evidence.jsonl').open('a') as output:
        output.write(json.dumps(evidence) + '\n')
    print('FUNDS_MATCH ' + stage + ' ' + str(combined), flush=True)

def release(order, seller):
    req('POST', '/api/v1/c2c/orders/' + order + '/release', seller, challenge(seller))
    return status(order, seller, 'COMPLETED')

def close_ad(ad):
    row = own_ad(ad)
    req('POST', '/api/v1/c2c/ads/' + ad + '/status', 'seller', {'revision': row['revision'], 'action': 'CLOSE', 'verification': challenge('seller')})
    return await_value(lambda: own_ad(ad), lambda row: row['status'] == 'CLOSED', 'Ad refund and closure')

def basic_funds():
    assert not fixture.get('fundsStarted'), 'Financial QA already started; inspect original submissions before continuing'
    fixture['initialTotals'] = totals(balances())
    fixture['fundsStarted'] = True
    save()
    reconcile('baseline')
    sell = publish('SELL', '30')
    fixture['sellAd'] = sell
    save()
    reconcile('sell-ad-reserve')
    order = create(sell, 'buyer', '2', lost_reply=True)
    identity = order['orderId']
    req('GET', '/api/v1/c2c/orders/' + identity, 'other', expected=(403,))
    req('POST', '/api/v1/c2c/orders/' + identity + '/paid', 'buyer', {'proofId': None})
    reconcile('paid-holds-escrow')
    release(identity, 'seller')
    reconcile('seller-release-net-and-fee')
    canceled = create(sell, 'buyer', '3')
    req('POST', '/api/v1/c2c/orders/' + canceled['orderId'] + '/cancel', 'buyer', {'reason': 'Synthetic buyer cancellation before payment'})
    status(canceled['orderId'], 'buyer', 'CANCELED')
    reconcile('sell-cancel-restocks-ad')
    buy = publish('BUY', '10')
    fixture['buyAd'] = buy
    save()
    sold = create(buy, 'buyer', '4')
    reconcile('taker-seller-reserve')
    req('POST', '/api/v1/c2c/orders/' + sold['orderId'] + '/cancel', 'seller', {'reason': 'Synthetic unpaid buy order cancellation'})
    status(sold['orderId'], 'buyer', 'CANCELED')
    reconcile('buy-order-actual-refund')
    fixture['basicFundsPassed'] = True
    save()

def advanced_funds():
    assert fixture.get('basicFundsPassed') and (not fixture.get('advancedStarted'))
    fixture['advancedStarted'] = True
    save()
    expired = create(fixture['buyAd'], 'other', '1')
    paid = create(fixture['sellAd'], 'buyer', '1')
    fixture['expiryOrder'] = expired['orderId']
    fixture['paidAcrossExpiry'] = paid['orderId']
    save()
    req('POST', '/api/v1/c2c/orders/' + paid['orderId'] + '/paid', 'buyer', {'proofId': None})
    print('Waiting for real 60-second unpaid deadline while a paid order remains held', flush=True)
    await_value(lambda: req('GET', '/api/v1/c2c/orders/' + expired['orderId'], 'other'), lambda row: row['status'] == 'EXPIRED', 'Real unpaid expiry refund', seconds=80)
    assert req('GET', '/api/v1/c2c/orders/' + paid['orderId'], 'buyer')['status'] == 'PAID'
    release(paid['orderId'], 'seller')
    reconcile('unpaid-expiry-refund-paid-order-never-auto-refunds')
    disputed = create(fixture['sellAd'], 'buyer', '2')
    req('POST', '/api/v1/c2c/orders/' + disputed['orderId'] + '/paid', 'buyer', {'proofId': None})
    appeal = req('POST', '/api/v1/c2c/orders/' + disputed['orderId'] + '/appeal', 'buyer', {'reason': 'Synthetic disputed off-platform payment; refund decision test'})
    path = '/api/v1/admin/c2c/appeals/' + appeal['appealId'] + '/decision'
    body = {'decision': 'REFUND', 'reason': 'Synthetic evidence independently checked; return escrow to original seller'}
    req('POST', path, 'admin', body, expected=(403,))
    approved('POST', path, body)
    status(disputed['orderId'], 'buyer', 'CANCELED')
    reconcile('dual-approved-appeal-refund-original-seller')
    sold = create(fixture['buyAd'], 'other', '1')
    req('POST', '/api/v1/c2c/orders/' + sold['orderId'] + '/paid', 'seller', {'proofId': None})
    appeal = req('POST', '/api/v1/c2c/orders/' + sold['orderId'] + '/appeal', 'other', {'reason': 'Synthetic received payment confirmation dispute'})
    approved('POST', '/api/v1/admin/c2c/appeals/' + appeal['appealId'] + '/decision', {'decision': 'RELEASE', 'reason': 'Synthetic actual receipt independently verified; release only original buyer'})
    status(sold['orderId'], 'other', 'COMPLETED')
    reconcile('dual-approved-appeal-release-net-fee')
    ad = publish('SELL', '5')
    ongoing = create(ad, 'buyer', '2')
    close_ad(ad)
    reconcile('closed-ad-refunds-only-unallocated-stock')
    req('POST', '/api/v1/c2c/orders/' + ongoing['orderId'] + '/paid', 'buyer', {'proofId': None})
    release(ongoing['orderId'], 'seller')
    reconcile('closed-ad-existing-order-still-releases')
    fixture['advancedFundsPassed'] = True
    save()

def uncertain_core():
    assert fixture.get('basicFundsPassed') and (not fixture.get('unknownStarted'))
    fixture['unknownStarted'] = True
    save()
    core = json.loads((ROOT / 'processes.json').read_text())['core']['pid']
    assert str(ROOT) in subprocess.check_output(['ps', '-p', str(core), '-o', 'command='], text=True)
    try:
        os.kill(core, signal.SIGSTOP)
        ad = publish('SELL', '1', wait=False)
        fixture['unknownAd'] = ad
        save()
        before = req('GET', '/api/v1/admin/c2c/fund-intents?ownerId=' + ad, 'admin')['items']
        assert len(before) == 1 and before[0]['status'] == 'PREPARED', before
        original = before[0]['transferId']
        for _ in range(8):
            time.sleep(1)
        assert own_ad(ad)['status'] == 'RESERVING'
        assert req('GET', '/api/v1/admin/c2c/reconciliation', 'admin')['status'] == 'PENDING'
        same = req('GET', '/api/v1/admin/c2c/fund-intents?ownerId=' + ad, 'admin')['items']
        assert len(same) == 1 and same[0]['transferId'] == original, same
        print('Core paused: original ad remains RESERVING with one fixed transfer identity', flush=True)
    finally:
        os.kill(core, signal.SIGCONT)
    await_value(lambda: own_ad(ad), lambda row: row['status'] == 'ACTIVE', 'Resume original Core intent', seconds=60)
    after = req('GET', '/api/v1/admin/c2c/fund-intents?ownerId=' + ad, 'admin')['items']
    assert len(after) == 1 and after[0]['transferId'] == original and (after[0]['status'] == 'APPLIED'), after
    reconcile('core-unknown-original-intent-resumes-once')
    close_ad(ad)
    reconcile('unknown-ad-final-refund')
    fixture['unknownFundsPassed'] = True
    save()

def finish_funds():
    for row in req('GET', '/api/v1/c2c/me/ads', 'seller')['items']:
        if row['status'] not in ('CLOSED', 'REJECTED'):
            close_ad(row['adId'])
    reconcile('all-ads-closed-final')
    assert sql("SELECT count(*) FROM c2c_fund_intents WHERE status<>'APPLIED'") == '0'
    assert sql("SELECT count(*) FROM c2c_orders WHERE status NOT IN ('COMPLETED','CANCELED','EXPIRED','FAILED')") == '0'
    assert sql("SELECT count(*) FROM c2c_ads WHERE status NOT IN ('CLOSED','REJECTED')") == '0'
    fixture['allFundsTerminal'] = True
    save()
    print('All C2C orders, ads and original fund intents terminal', flush=True)

def gateway_crash_recovery():
    assert fixture.get('allFundsTerminal') and (not fixture.get('crashStarted'))
    fixture['crashStarted'] = True
    fixture['allFundsTerminal'] = False
    save()
    processes = json.loads((ROOT / 'processes.json').read_text())
    core = processes['core']['pid']
    gateway = processes['gateway']['pid']
    for pid in (core, gateway):
        assert str(ROOT) in subprocess.check_output(['ps', '-p', str(pid), '-o', 'command='], text=True)
    try:
        os.kill(core, signal.SIGSTOP)
        ad = publish('SELL', '1', wait=False)
        fixture['crashAd'] = ad
        save()
        before = req('GET', '/api/v1/admin/c2c/fund-intents?ownerId=' + ad, 'admin')['items']
        assert len(before) == 1 and before[0]['status'] == 'PREPARED', before
        fixture['crashTransferId'] = before[0]['transferId']
        save()
        time.sleep(2)
        os.kill(gateway, signal.SIGKILL)
        print('Killed only owned local Gateway with one durable unresolved ad reserve', flush=True)
    finally:
        os.kill(core, signal.SIGCONT)
    for _ in range(40):
        try:
            os.kill(gateway, 0)
        except ProcessLookupError:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError('Old owned gateway has not exited')
    # SIGKILL 后保留旧驱动心跳的保护期；等待失效，再启动同一个隔离 Gateway。
    time.sleep(12)
    subprocess.run([sys.executable, str(ROOT / 'runtime.py'), 'gateway'], check=True)
    resume_crash()

def resume_crash():
    ad = fixture['crashAd']
    deadline = time.monotonic() + 70
    while True:
        try:
            state = req('GET', '/api/v1/c2c/status')
            if state.get('spotReady'):
                break
        except (OSError, AssertionError):
            pass
        assert time.monotonic() < deadline, 'Gateway recovery readiness'
        time.sleep(0.5)
    await_value(lambda: own_ad(ad), lambda row: row['status'] == 'ACTIVE', 'Durable ad reserve after process crash', seconds=60)
    after = req('GET', '/api/v1/admin/c2c/fund-intents?ownerId=' + ad, 'admin')['items']
    assert len(after) == 1 and after[0]['status'] == 'APPLIED' and (after[0]['transferId'] == fixture['crashTransferId']), after
    reconcile('gateway-crash-recovers-original-durable-reserve')
    close_ad(ad)
    reconcile('gateway-crash-final-refund')
    fixture['gatewayCrashPassed'] = True
    save()
    finish_funds()
if __name__ == '__main__':
    action = sys.argv[1]
    if action == 'check':
        print('Owned SPOT FUSED QA runtime verified; no financial command sent')
    elif action == 'users':
        bootstrap_users()
    elif action == 'market':
        setup_market()
    elif action == 'trading':
        setup_trading()
    elif action == 'funds':
        basic_funds()
    elif action == 'advanced':
        advanced_funds()
    elif action == 'unknown':
        uncertain_core()
    elif action == 'finish':
        finish_funds()
    elif action == 'crash':
        gateway_crash_recovery()
    elif action == 'resume-crash':
        resume_crash()
    else:
        raise ValueError(action)
