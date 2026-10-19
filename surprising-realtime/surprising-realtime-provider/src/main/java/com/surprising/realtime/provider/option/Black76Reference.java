package com.surprising.realtime.provider.option;

import org.apache.commons.math3.special.Erf;

/** Black-76 只读参考模型：远期价格、零贴现、ACT/365F；不参与资金或风险准入。 */
public final class Black76Reference {
    private Black76Reference() {}

    public record Greeks(double impliedVolatility, double forwardDelta, double forwardGamma,
                  double vegaPerVolPoint, double thetaPerDay) {}

    static Greeks infer(boolean call, double forward, double strike, double premium, double years) {
        if (!positive(forward) || !positive(strike) || !positive(premium) || !positive(years)) return null;
        double intrinsic = Math.max(call ? forward - strike : strike - forward, 0);
        double upper = call ? forward : strike;
        // 零时间价值无法唯一反推波动率；越界报价不能靠钳制价格伪造 Greeks。
        if (premium <= intrinsic || premium >= upper) return null;
        double timeValue = premium - intrinsic;
        double headroom = upper - premium;
        // 距价格边界只有数个浮点单位时，IV 已不可辨识，不能返回任意迭代点。
        if (Math.min(timeValue, headroom) <= Math.ulp(upper) * 16) return null;
        double low = 0, high = 1;
        while (price(call, forward, strike, high) < premium && high < 32) high *= 2;
        double tolerance = Math.max(Math.ulp(premium) * 8, Math.min(timeValue, headroom) * 1e-10);
        double width = high;
        boolean converged = false;
        for (int i = 0; i < 160; i++) {
            width = (low + high) / 2;
            double value = price(call, forward, strike, width);
            if (Math.abs(value - premium) <= tolerance) { converged = true; break; }
            if (value < premium) low = width;
            else high = width;
        }
        if (!converged || width <= 0) return null;
        double rootTime = Math.sqrt(years);
        double volatility = width / rootTime;
        double d1 = (Math.log(forward) - Math.log(strike)) / width + width / 2;
        double density = Math.exp(-d1 * d1 / 2) / Math.sqrt(2 * Math.PI);
        double delta = call ? cdf(d1) : -cdf(-d1);
        double gamma = density / (forward * width);
        double vega = forward * density * rootTime / 100;
        double theta = -forward * density * volatility / (2 * rootTime * 365);
        if (!Double.isFinite(volatility) || !Double.isFinite(delta) || !Double.isFinite(gamma)
                || !Double.isFinite(vega) || !Double.isFinite(theta)) return null;
        return new Greeks(volatility, delta, gamma, vega, theta);
    }

    private static double price(boolean call, double forward, double strike, double width) {
        if (width == 0) return Math.max(call ? forward - strike : strike - forward, 0);
        double d1 = (Math.log(forward) - Math.log(strike)) / width + width / 2;
        double d2 = d1 - width;
        // 先算价外一侧，再用平价关系恢复价内侧，避免大额价内价格相减丢失时间价值。
        if (forward >= strike) {
            double put = strike * cdf(-d2) - forward * cdf(-d1);
            return call ? forward - strike + put : put;
        }
        double value = forward * cdf(d1) - strike * cdf(d2);
        return call ? value : strike - forward + value;
    }

    private static double cdf(double value) { return 0.5 * Erf.erfc(-value / Math.sqrt(2)); }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
