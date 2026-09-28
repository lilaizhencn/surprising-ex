#!/usr/bin/env python3
"""Read-only readiness check for the local 20-symbol U perpetual environment."""
import concurrent.futures
import json
import os
from datetime import datetime, timezone
from pathlib import Path
import sys
import urllib.parse
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:9094"
RUNTIME = Path(os.environ.get("LOCAL_PERPETUAL_DIR", str(Path.home() / ".local/share/surprising-ex/perpetual-pmm-20")))
INSTRUMENT_IDS = json.loads((RUNTIME / "market-ids.json").read_text())


def get(path):
    request = urllib.request.Request(BASE + path, headers={"X-Product-Line": "LINEAR_PERPETUAL"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)
    except Exception as error:
        raise RuntimeError(f"{path}: {error}") from error


def check(item):
    symbol, instrument_id = item
    try:
        query = urllib.parse.urlencode({"instrumentId": str(instrument_id)})
        instrument = get("/api/v1/gateway/instrument/latest?" + query)
        assert int(instrument["priceTickUnits"]) * int(instrument["contractMultiplierPpm"]) == int(instrument["notionalMultiplierUnits"]) * 1000000, "contract quantity differs from Core notional math"
        index = get("/api/v1/gateway/price-index/latest?" + query)
        mark = get("/api/v1/gateway/price-mark/latest?" + query)
        book = get("/api/v1/gateway/trading-market/orderbook?depth=5&" + query)
        assert index["validComponentCount"] == 3, "fewer than 3 valid index sources"
        assert mark["markPriceUnits"] > 0, "invalid mark ticks"
        assert book["bids"] and book["asks"], "empty order book"
        age = (datetime.now(timezone.utc) - datetime.fromisoformat(mark["eventTime"].replace("Z", "+00:00"))).total_seconds()
        assert age < 15, f"mark price stale: {age:.1f}s"
        return {"symbol": symbol, "instrumentId": instrument_id, "ok": True, "index": index["indexPrice"],
                "mark": mark["markPrice"], "bids": len(book["bids"]), "asks": len(book["asks"])}
    except Exception as error:
        return {"symbol": symbol, "instrumentId": instrument_id, "ok": False, "error": str(error)}


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(check, INSTRUMENT_IDS.items()))
    print(json.dumps({"productLine": "LINEAR_PERPETUAL", "passed": sum(r["ok"] for r in results),
                      "total": len(results), "symbols": results}, indent=2))
    sys.exit(0 if all(r["ok"] for r in results) else 1)
