import importlib.util
import unittest
from datetime import datetime, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location('liquidity', Path(__file__).parents[1] / 'check-linear-liquidity.py')
liquidity = importlib.util.module_from_spec(spec)
spec.loader.exec_module(liquidity)


class LinearLiquidityTest(unittest.TestCase):
    def setUp(self):
        self.instrument = dict(instrumentId=1, contractType='LINEAR_PERPETUAL', status='TRADING',
                               notionalMultiplierUnits=1, marketOrderEnabled=True,
                               minQuantitySteps=1, maxQuantitySteps=10**9,
                               minNotionalUnits=1, maxNotionalUnits=10**15)
        self.book = dict(instrumentId='1', sequence=1, eventTime='2026-10-01T00:00:00Z',
                         bids=[dict(priceTicks=9999, quantitySteps=10), dict(priceTicks=9998, quantitySteps=10)],
                         asks=[dict(priceTicks=10000, quantitySteps=10), dict(priceTicks=10001, quantitySteps=10)])
        self.now = datetime(2026, 10, 1, tzinfo=timezone.utc)

    def assess(self, amount=200000):
        return liquidity.assess(self.instrument, self.book, [amount], 1, now=self.now)['rows']

    def test_exact_one_basis_point_boundary_and_sell_rounding(self):
        buy, sell = self.assess()
        self.assertTrue(buy['fullDepthWithinWorstSlippage'])
        self.assertEqual(buy['worstSlippagePpm'], '100.00000000')
        self.assertEqual(buy['averageSlippagePpm'], '50.00000000')
        self.assertEqual(sell['requestedQuantitySteps'], 21)
        self.assertFalse(sell['fullDepthWithinAverageSlippage'])
        self.assertEqual(sell['unfilledQuantitySteps'], 1)

    def test_partial_fill_never_passes_even_when_average_slippage_is_small(self):
        for row in self.assess(1000000):
            self.assertFalse(row['fullDepthWithinAverageSlippage'])
            self.assertFalse(row['fullDepthWithinWorstSlippage'])

    def test_native_ascending_bids_are_normalized_without_mutating_input(self):
        expected = self.assess()
        self.book['bids'].reverse()
        self.assertEqual(self.assess(), expected)
        self.assertEqual(self.book['bids'][0]['priceTicks'], 9998)

    def test_duplicate_prices_still_rejected(self):
        self.book['bids'].append(dict(self.book['bids'][0]))
        with self.assertRaisesRegex(ValueError, 'distinct'):
            self.assess()

    def test_worst_price_can_fail_while_average_passes(self):
        self.book['asks'][1]['priceTicks'] = 10002
        buy = self.assess(200000)[0]
        self.assertTrue(buy['fullDepthWithinAverageSlippage'])
        self.assertFalse(buy['fullDepthWithinWorstSlippage'])
        self.assertEqual(buy['quantityWithinPriceBand'], 10)

    def test_instrument_limits_are_independent_of_depth(self):
        self.instrument['maxQuantitySteps'] = 1
        buy = self.assess(200000)[0]
        self.assertTrue(buy['fullDepthWithinWorstSlippage'])
        self.assertFalse(buy['instrumentOrderLimitsAllow'])

    def test_rejects_stale_crossed_or_wrong_product_inputs(self):
        self.book['eventTime'] = '2026-09-30T00:00:00Z'
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.assess()
        self.setUp()
        self.book['bids'][0]['priceTicks'] = 10000
        with self.assertRaisesRegex(ValueError, 'crossed'):
            self.assess()
        self.setUp()
        self.instrument['contractType'] = 'INVERSE_PERPETUAL'
        with self.assertRaisesRegex(ValueError, 'only linear'):
            self.assess()

    def test_decimal_amount_and_asset_scale_are_exact(self):
        rows = liquidity.assess(self.instrument, self.book, ['2000.00'], 100, now=self.now)['rows']
        self.assertEqual(rows[0]['requestedQuantitySteps'], 20)
        self.assertEqual(rows[0]['filledNotional'], '2000.10000000')


if __name__ == '__main__':
    unittest.main()
