#!/usr/bin/env python3
"""
Continuous High-Fidelity BTC Maker & Matcher Daemon for Surprising EX.
Maintains two-sided 10-level order book depth around live mark/index price
and executes periodic micro-trades for live ticker and recent fills.
"""
import json
import random
import time
import urllib.request

GATEWAY_BASE = "http://127.0.0.1:9094"
INSTRUMENT_ID = "604"  # BTC-USDT Linear Perpetual
USER_MAKER_BUY = 2
USER_MAKER_SELL = 3
LEVELS = 8


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


def cancel_all_orders():
    # Cancel open orders for maker users
    for uid in (USER_MAKER_BUY, USER_MAKER_SELL):
        http_post("/api/v1/trading/orders/cancel-all", {
            "userId": uid,
            "instrumentId": INSTRUMENT_ID
        })


def get_current_mark_ticks():
    res = http_get(f"/api/v1/gateway/price-mark/latest?instrumentId={INSTRUMENT_ID}")
    if res and "markPrice" in res and res["markPrice"]:
        # priceTickUnits is 10000000 (0.1 USDT), markPrice is float
        return int(float(res["markPrice"]) * 10)
    return 758000


def seed_depth_and_trade():
    print("BTC Market Maker Daemon started.")
    nonce = int(time.time())
    
    while True:
        try:
            anchor_ticks = get_current_mark_ticks()
            if anchor_ticks <= 0:
                anchor_ticks = 758000
            
            # Place buy ladder
            for i in range(1, LEVELS + 1):
                price = anchor_ticks - (i * 2 + random.randint(0, 1))
                qty = random.randint(1, 5) * 2
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_BUY,
                    "clientOrderId": f"mm-b-{i}-{nonce % 10000}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "BUY",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": price,
                    "quantitySteps": qty,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })

            # Place sell ladder
            for i in range(1, LEVELS + 1):
                price = anchor_ticks + (i * 2 + random.randint(0, 1))
                qty = random.randint(1, 5) * 2
                http_post("/api/v1/trading/orders", {
                    "userId": USER_MAKER_SELL,
                    "clientOrderId": f"mm-s-{i}-{nonce % 10000}",
                    "instrumentId": INSTRUMENT_ID,
                    "side": "SELL",
                    "orderType": "LIMIT",
                    "timeInForce": "GTC",
                    "priceTicks": price,
                    "quantitySteps": qty,
                    "marginMode": "CROSS",
                    "positionSide": "NET",
                    "reduceOnly": False,
                    "postOnly": False
                })

            # Random micro-match: trade 1 contract across the spread
            trade_side = "BUY" if random.random() > 0.5 else "SELL"
            trade_price = anchor_ticks + (1 if trade_side == "BUY" else -1)
            trade_user = USER_MAKER_BUY if trade_side == "BUY" else USER_MAKER_SELL
            http_post("/api/v1/trading/orders", {
                "userId": trade_user,
                "clientOrderId": f"trade-{nonce}",
                "instrumentId": INSTRUMENT_ID,
                "side": trade_side,
                "orderType": "LIMIT",
                "timeInForce": "IOC",
                "priceTicks": trade_price,
                "quantitySteps": 1,
                "marginMode": "CROSS",
                "positionSide": "NET",
                "reduceOnly": False,
                "postOnly": False
            })

            nonce += 1
        except Exception as err:
            print("Maker loop error:", err)
        
        time.sleep(2.0)


if __name__ == "__main__":
    seed_depth_and_trade()
