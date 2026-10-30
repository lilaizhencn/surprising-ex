package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalModels.Acceptance;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Compact auth wire field: revisionId:locale,revisionId:locale. IDs stay exact int64 values. */
final class LegalAcceptanceHeader {
    static final String NAME="X-Legal-Acceptance";
    private LegalAcceptanceHeader() { }
    static List<Acceptance> parse(String header) {
        if(header==null || header.isBlank())return List.of();
        if(header.length()>512)throw invalid();
        String[] parts=header.split(",",-1);
        if(parts.length>10)throw invalid();
        var values=new ArrayList<Acceptance>();var ids=new HashSet<Long>();
        for(String part:parts) {
            String[] fields=part.trim().split(":",-1);
            if(fields.length!=2 || !fields[0].matches("[1-9][0-9]{0,18}") || fields[1].isBlank())throw invalid();
            try {
                long id=Long.parseLong(fields[0]);
                if(!ids.add(id))throw invalid();
                values.add(new Acceptance(id,ContentLocale.require(fields[1]).code()));
            } catch(IllegalArgumentException invalid) { throw invalid(); }
        }
        return List.copyOf(values);
    }
    private static LegalProblem invalid() { return new LegalProblem(Kind.INVALID,"Invalid legal acknowledgement header."); }
}
