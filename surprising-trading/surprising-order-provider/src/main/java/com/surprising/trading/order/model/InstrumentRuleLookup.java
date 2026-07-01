package com.surprising.trading.order.model;

import java.util.Optional;

public interface InstrumentRuleLookup {

    Optional<InstrumentRule> currentRule(String symbol);
}
