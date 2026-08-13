package com.surprising.gateway.provider.option;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class BlackScholesCalculator {

    private static final double MIN_VOLATILITY = 0.000001d;
    private static final double MAX_VOLATILITY = 8.0d;
    private static final double MIN_TIME = 1.0e-9d;
    private static final int SOLVE_ITERATIONS = 100;

    private BlackScholesCalculator() {
    }

    static Result solve(double spot, double strike, double premium, double timeYears, boolean call) {
        requirePositive(spot, "underlying price");
        requirePositive(strike, "strike price");
        requirePositive(timeYears, "time to expiry");
        if (!Double.isFinite(premium) || premium <= 0.0d) {
            throw new IllegalArgumentException("option price must be positive");
        }
        double intrinsic = Math.max(call ? spot - strike : strike - spot, 0.0d);
        double upperBound = call ? spot : strike;
        if (premium <= intrinsic || premium >= upperBound) {
            throw new IllegalArgumentException("option price is outside implied-volatility bounds");
        }

        double low = MIN_VOLATILITY;
        double high = MAX_VOLATILITY;
        for (int i = 0; i < SOLVE_ITERATIONS; i++) {
            double mid = (low + high) / 2.0d;
            double value = price(spot, strike, timeYears, mid, call);
            if (value > premium) {
                high = mid;
            } else {
                low = mid;
            }
        }
        double volatility = (low + high) / 2.0d;
        double d1 = d1(spot, strike, timeYears, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeYears);
        double normalDensity = normalDensity(d1);
        double delta = call ? normalCdf(d1) : normalCdf(d1) - 1.0d;
        double gamma = normalDensity / (spot * volatility * Math.sqrt(timeYears));
        double theta = -(spot * normalDensity * volatility) / (2.0d * Math.sqrt(timeYears))
                + (call ? 1.0d : -1.0d) * strike * normalDensity(d2) * 0.0d;
        double vega = spot * normalDensity * Math.sqrt(timeYears);
        double rho = (call ? 1.0d : -1.0d) * strike * timeYears * normalCdf(call ? d2 : -d2);
        return new Result(volatility, delta, gamma, theta, vega, rho);
    }

    private static double price(double spot, double strike, double timeYears, double volatility, boolean call) {
        double d1 = d1(spot, strike, timeYears, volatility);
        double d2 = d1 - volatility * Math.sqrt(timeYears);
        if (call) {
            return spot * normalCdf(d1) - strike * normalCdf(d2);
        }
        return strike * normalCdf(-d2) - spot * normalCdf(-d1);
    }

    private static double d1(double spot, double strike, double timeYears, double volatility) {
        return (Math.log(spot / strike) + 0.5d * volatility * volatility * timeYears)
                / (volatility * Math.sqrt(timeYears));
    }

    private static double normalDensity(double value) {
        return Math.exp(-0.5d * value * value) / Math.sqrt(2.0d * Math.PI);
    }

    private static double normalCdf(double value) {
        return 0.5d * (1.0d + erf(value / Math.sqrt(2.0d)));
    }

    private static double erf(double value) {
        double sign = value < 0.0d ? -1.0d : 1.0d;
        double absolute = Math.abs(value);
        double t = 1.0d / (1.0d + 0.3275911d * absolute);
        double polynomial = (((((1.061405429d * t - 1.453152027d) * t) + 1.421413741d) * t
                - 0.284496736d) * t + 0.254829592d) * t;
        return sign * (1.0d - polynomial * Math.exp(-absolute * absolute));
    }

    private static void requirePositive(double value, String field) {
        if (!Double.isFinite(value) || value <= MIN_TIME) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }

    static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(12, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    record Result(double volatility, double delta, double gamma, double theta, double vega, double rho) {
    }
}
