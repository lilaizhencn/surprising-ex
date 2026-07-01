package com.surprising.adl.api.model;

import java.util.List;

public record AdlEventQueryResponse(
        int count,
        List<AdlEventResponse> events) {
}
