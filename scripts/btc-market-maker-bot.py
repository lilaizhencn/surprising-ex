#!/usr/bin/env python3
"""
Continuous High-Fidelity Two-Sided BTC Market Maker & Matcher Daemon for Surprising EX.
Maintains two-sided order book depth around live mark/index price and continuously
executes alternating matched micro-trades to drive live order books, chart candles, and ticks
while maintaining net delta near zero to prevent margin exhaustion.
"""
import json
import random
import sys
import time
import urllib.request

GATEWAY_BASE = "http://127.0.0.1:9094"
INSTRUMENT_ID = "604"  # BTC-USDT Linear Perpetual
USER_MAKER_BUY = 2
USER_MAKER_SELL = 3
LEVELS = 6


def http_post(path, data):
    url = f"{GATEWAY_BASE}{path}"
    headers = {
        "Content-Type": "application/json",
        "X-Product-Line": "LINEAR_PERPETUAL"
    }
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=5) as res:
            return json.loads(res.read().decode("utf-8"))
    except Exception as e:
        return {"error": str(e)}


def http_get(path):
    url = f"{GATEWAY_BASE}{path}"
    headers = {"X-Product-Line": "LINEAR_PERPETUAL"}
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as res:
            return json.loads(res.read().decode("utf-8"))
    except Exception as e:
        return {"error": str(e)}


def cancel_maker_orders():
    for uid in (USER_MAKER_BUY, USER_MAKER_SELL):
        http_post("/api/v1/trading/orders/cancel-open", {
            "userId": uid,
            "instrumentId": INSTRUMENT_ID
        })


def get_current_mark_ticks():
    res = http_get(f"/api/v1/gateway/price-mark/latest?instrumentId={INSTRUMENT_ID}")
    if res and isinstance(res, dict) and "markPrice" in res and res["markPrice"]:
        try:
            return int(float(res["markPrice"]) * 10)
        except Exception:
            pass
    return 758000


def run_market_maker():
    print("BTC Market Maker Daemon running: active depth ladder and balanced matched fills.", flush=True)
    nonce = int(time.time() * 1000) % 10000000
    turn = 0

    while True:
        try:
            # 1. Clear previous open depth orders to release margin
            cancel_maker_orders()
            time.sleep(0.05)

            # 2. Get current price anchor
            anchor_ticks = get_current_mark_ticks()
            if anchor_ticks <= 0:
                anchor_ticks = 758000

            # 3. Micro price drift (-1 to +1 tick)
            drift = random.choice([-1, 0, 1])
            mid_ticks = anchor_ticks + drift

            # 4. Place buy ladder (USER_MAKER_BUY)
            for i in range(1, LEVELS + 1):
                price = mid_ticks - (i * 2)
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_BUY,
                    "clientOrderId": f"b-{i}-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "BUY",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })

            # 5. Place sell ladder (USER_MAKER_SELL)
            for i in range(1, LEVELS + 1):
                price = mid_ticks + (i * 2)
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_SELL,
                    "clientOrderId": f"s-{i}-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "SELL",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })

            # 6. Balanced Alternating Matched Micro-Trade
            match_price = mid_ticks
            if turn % 2 == 0:
                # Maker: USER_MAKER_SELL posts Limit Sell, Taker: USER_MAKER_BUY hits Limit Buy
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_SELL,
                    "clientOrderId": f"ms-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "SELL",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": match_price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_BUY,
                    "clientOrderId": f"tb-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "BUY",
                    "orderType": "LIMIT",
                    "timeInForce": "IOC",
                    "priceTicks": match_price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })
            else:
                # Maker: USER_MAKER_BUY posts Limit Buy, Taker: USER_MAKER_SELL hits Limit Sell
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_BUY,
                    "clientOrderId": f"mb-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "BUY",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": match_price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_SELL,
                    "clientOrderId": f"ts-{nonce}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "SELL",
                    "orderType": "LIMIT",
                    "timeInForce": "IOC",
                    "priceTicks": match_price,
                    "quantitySteps": 1,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })

            nonce += 1
            turn += 1
            if turn % 10 == 0:
                print(f"[Maker] Completed {turn} cycles. Current mid={mid_ticks/10.0:.1f}", flush=True)
        except Exception as err:
            print("[Maker] Error in cycle:", err, flush=True)

        time.sleep(1.5)


if __name__ == "__main__":
    run_market_maker()
