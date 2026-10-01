#!/usr/bin/env python3
"""Read-only linear contract depth check. Input: native instrument/book API JSON."""
import argparse
import json
from datetime import datetime, timezone
from decimal import Decimal, localcontext
from fractions import Fraction
from pathlib import Path

PPM = 1_000_000


def positive_int(value, name):
    if type(value) is not int or value <= 0:
        raise ValueError(f"{name} must be a positive integer")
    return value


def number(value):
    with localcontext() as context:
        context.prec = 50
        return str((Decimal(value.numerator) / Decimal(value.denominator)).quantize(Decimal('0.00000001')))


def assess(instrument, book, notionals, scale, max_slippage_ppm=100, max_age_seconds=3, now=None):
    positive_int(scale, 'quote scale units')
    if type(max_slippage_ppm) is not int or not 0 <= max_slippage_ppm <= PPM:
        raise ValueError('slippage ppm must be between 0 and 1000000')
    if max_age_seconds <= 0:
        raise ValueError('max age must be positive')
    if instrument['contractType'] not in ('LINEAR_PERPETUAL', 'LINEAR_DELIVERY'):
        raise ValueError('only linear perpetual/delivery contracts are supported')
    if instrument['status'] != 'TRADING':
        raise ValueError('instrument is not TRADING')
    if str(instrument['instrumentId']) != str(book['instrumentId']):
        raise ValueError('instrument/book identity mismatch')
    timestamp = datetime.fromisoformat(book['eventTime'].replace('Z', '+00:00'))
    if timestamp.tzinfo is None:
        raise ValueError('book timestamp must include timezone')
    age = ((now or datetime.now(timezone.utc)) - timestamp).total_seconds()
    if age < 0 or age > max_age_seconds:
        raise ValueError('book is stale or future-dated')
    multiplier = positive_int(instrument['notionalMultiplierUnits'], 'notional multiplier')
    book = dict(book)
    for side, descending in [('bids', True), ('asks', False)]:
        for level in book[side]:
            positive_int(level['priceTicks'], 'price ticks')
            positive_int(level['quantitySteps'], 'quantity steps')
        # Native depth may serialize bids ascending; normalize at the diagnostic boundary.
        book[side] = sorted(book[side], key=lambda level: level['priceTicks'], reverse=descending)
        previous = None
        for level in book[side]:
            price = positive_int(level['priceTicks'], 'price ticks')
            positive_int(level['quantitySteps'], 'quantity steps')
            if previous is not None and (price >= previous if descending else price <= previous):
                raise ValueError('book levels must have distinct sorted prices')
            previous = price
    if not book['bids'] or not book['asks']:
        raise ValueError('two-sided depth is required')
    bid, ask = book['bids'][0]['priceTicks'], book['asks'][0]['priceTicks']
    if bid >= ask:
        raise ValueError('book is crossed or locked')
    mid = Fraction(bid + ask, 2)
    rows = []
    for side, levels, sign in [('BUY', book['asks'], 1), ('SELL', book['bids'], -1)]:
        best = levels[0]['priceTicks']
        bounded_steps = sum(level['quantitySteps'] for level in levels
                            if sign * (level['priceTicks'] - best) * PPM <= best * max_slippage_ppm)
        for notional in notionals:
            amount = Fraction(str(notional)) * scale
            if amount <= 0 or amount.denominator != 1:
                raise ValueError('notional must be positive and representable in quote units')
            per_step = best * multiplier
            requested = (amount.numerator + per_step - 1) // per_step
            remaining, weighted, worst = requested, 0, best
            for level in levels:
                take = min(remaining, level['quantitySteps'])
                weighted += take * level['priceTicks']
                remaining -= take
                if take:
                    worst = level['priceTicks']
                if remaining == 0:
                    break
            filled = requested - remaining
            average = Fraction(weighted, filled) if filled else Fraction(best)
            average_slippage = sign * (average - best) * PPM / best
            worst_slippage = Fraction(sign * (worst - best) * PPM, best)
            mid_cost = sign * (average - mid) * PPM / mid
            order_notional = requested * per_step
            order_allowed = (instrument['marketOrderEnabled'] is True
                             and instrument['minQuantitySteps'] <= requested <= instrument['maxQuantitySteps']
                             and instrument['minNotionalUnits'] <= order_notional <= instrument['maxNotionalUnits'])
            rows.append(dict(side=side, requestedNotional=str(notional), requestedQuantitySteps=requested,
                             filledQuantitySteps=filled, unfilledQuantitySteps=remaining,
                             filledNotional=number(Fraction(weighted * multiplier, scale)),
                             averagePriceTicks=number(average), averageSlippagePpm=number(average_slippage),
                             worstSlippagePpm=number(worst_slippage), midPriceCostPpm=number(mid_cost),
                             quantityWithinPriceBand=bounded_steps,
                             notionalAtBestWithinPriceBand=number(Fraction(bounded_steps * per_step, scale)),
                             instrumentOrderLimitsAllow=order_allowed,
                             fullDepthWithinAverageSlippage=remaining == 0 and average_slippage <= max_slippage_ppm,
                             fullDepthWithinWorstSlippage=remaining == 0 and worst_slippage <= max_slippage_ppm))
    return dict(instrumentId=book['instrumentId'], contractType=instrument['contractType'],
                sequence=book['sequence'], eventTime=book['eventTime'], bookAgeSeconds=age,
                maxSlippagePpm=max_slippage_ppm, quoteScaleUnits=scale, rows=rows,
                scope='Displayed depth estimate only; excludes fees, concurrent fills, account risk and execution latency. '
                      'Quantity is rounded up from requested notional at the pre-order best price. '
                      'Partial-fill price statistics never qualify as a full-depth pass.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instrument-json', type=Path, required=True)
    parser.add_argument('--book-json', type=Path, required=True)
    parser.add_argument('--quote-scale-units', type=int, required=True, help='Use the quote asset scale_units from the catalog')
    parser.add_argument('--notionals', nargs='+', default=['100000', '1000000', '3000000', '5000000'])
    parser.add_argument('--max-slippage-ppm', type=int, default=100, help='100 ppm = 0.01%% = 1 basis point')
    parser.add_argument('--max-age-seconds', type=float, default=3)
    args = parser.parse_args()
    try:
        result = assess(json.loads(args.instrument_json.read_text()), json.loads(args.book_json.read_text()),
                        args.notionals, args.quote_scale_units, args.max_slippage_ppm, args.max_age_seconds)
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(2, f'Invalid input: {error}\n')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if all(row['instrumentOrderLimitsAllow'] and row['fullDepthWithinWorstSlippage']
                    for row in result['rows']) else 1


if __name__ == '__main__':
    raise SystemExit(main())
