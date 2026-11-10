#!/usr/bin/env python3
"""
Continuous High-Fidelity Two-Sided BTC Market Maker & Matcher Daemon for Surprising EX.
Maintains continuous two-sided order book depth without flickering/wiping,
and continuously executes matched micro-trades to drive live order books, chart candles, and ticks.
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


def place_order(user_id, side, price_ticks, qty_steps=1, tif="GTC", client_order_id=None):
    return http_post("/api/v1/trading/orders", {
        "userId": user_id,
        "clientOrderId": client_order_id or f"m-{user_id}-{int(time.time()*1000)%10000000}",
        "instrumentId": INSTRUMENT_ID,
        "side": side,
        "orderType": "LIMIT",
        "timeInForce": tif,
        "priceTicks": price_ticks,
        "quantitySteps": qty_steps,
        "marginMode": "CROSS",
        "positionSide": "NET",
        "reduceOnly": False,
        "postOnly": False,
        "takeProfitPriceTicks": 0,
        "stopLossPriceTicks": 0
    })


def refresh_ladder(anchor_ticks, nonce):
    """Place continuous 8-level buy and sell depth ladder around anchor."""
    for i in range(1, LEVELS + 1):
        buy_price = anchor_ticks - (i * 2)
        qty = random.randint(1, 4)
        place_order(USER_MAKER_BUY, "BUY", buy_price, qty, "GTC", f"b-{i}-{nonce}")

        sell_price = anchor_ticks + (i * 2)
        qty = random.randint(1, 4)
        place_order(USER_MAKER_SELL, "SELL", sell_price, qty, "GTC", f"s-{i}-{nonce}")


def run_market_maker():
    print("BTC Market Maker Daemon running with flicker-free continuous depth.", flush=True)
    nonce = int(time.time() * 1000) % 10000000
    turn = 0

    # Initial setup: clear and place ladder
    cancel_maker_orders()
    anchor_ticks = get_current_mark_ticks()
    if anchor_ticks <= 0:
        anchor_ticks = 758000
    refresh_ladder(anchor_ticks, nonce)
    last_refresh_time = time.time()
    last_anchor = anchor_ticks

    while True:
        try:
            nonce += 1
            turn += 1

            curr_mark = get_current_mark_ticks()
            if curr_mark <= 0:
                curr_mark = last_anchor

            # Check if ladder needs realignment (drift > 12 ticks or every 60s)
            now = time.time()
            if abs(curr_mark - last_anchor) >= 12 or (now - last_refresh_time) > 60:
                cancel_maker_orders()
                last_anchor = curr_mark
                refresh_ladder(last_anchor, nonce)
                last_refresh_time = now

            # Execute alternating matched micro-trade to drive candles & trades
            match_drift = random.choice([-1, 0, 1])
            match_price = last_anchor + match_drift
            qty = 1

            if turn % 2 == 0:
                # Sell Maker, Buy Taker (IOC)
                place_order(USER_MAKER_SELL, "SELL", match_price, qty, "GTC", f"ms-{nonce}")
                place_order(USER_MAKER_BUY, "BUY", match_price, qty, "IOC", f"tb-{nonce}")
                # Reinforce inner bid ladder
                place_order(USER_MAKER_BUY, "BUY", match_price - 2, 1, "GTC", f"rb-{nonce}")
            else:
                # Buy Maker, Sell Taker (IOC)
                place_order(USER_MAKER_BUY, "BUY", match_price, qty, "GTC", f"mb-{nonce}")
                place_order(USER_MAKER_SELL, "SELL", match_price, qty, "IOC", f"ts-{nonce}")
                # Reinforce inner ask ladder
                place_order(USER_MAKER_SELL, "SELL", match_price + 2, 1, "GTC", f"rs-{nonce}")

            if turn % 10 == 0:
                print(f"[Maker] Cycle {turn}. Mid price={last_anchor/10.0:.1f}, matched at {match_price/10.0:.1f}", flush=True)

        except Exception as err:
            print("[Maker] Error in cycle:", err, flush=True)

        time.sleep(1.0)


if __name__ == "__main__":
    run_market_maker()
