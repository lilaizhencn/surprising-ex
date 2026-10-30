package com.surprising.gateway.provider.legal.controller;

import com.surprising.gateway.provider.content.model.ContentLocale;
import com.surprising.gateway.provider.content.support.RichArticle;
import com.surprising.gateway.provider.legal.exception.LegalProblem;
import com.surprising.gateway.provider.legal.exception.LegalProblem.Kind;
import com.surprising.gateway.provider.legal.model.LegalModels.*;
import java.util.LinkedHashMap;

final class LegalParameters {
    private LegalParameters() { }
    static Content content(SaveRequest request) {
        if(request==null || request.type()==null)throw invalid("A document type is required.");
        String label=request.revisionLabel()==null?"":request.revisionLabel().trim();
        if(!label.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))throw invalid("Use a revision label of 1–40 letters, numbers, dots, hyphens or underscores.");
        if(request.translations()==null || request.translations().isEmpty() || request.translations().size()>12)throw invalid("Provide one to twelve translations.");
        var values=new LinkedHashMap<String,Translation>();
        for(var input:request.translations()) {
            if(input==null)throw invalid("A translation cannot be empty.");
            String locale=locale(input.locale());
            String title=input.title()==null?"":input.title().trim();
            if(title.isBlank() || title.length()>160)throw invalid("Translation titles must contain 1–160 characters.");
            String html;
            try { html=RichArticle.cleanLegal(input.bodyHtml()).html(); }
            catch(IllegalArgumentException invalid) { throw invalid(invalid.getMessage()); }
            if(values.putIfAbsent(locale,new Translation(locale,title,html))!=null)throw invalid("Duplicate translation language.");
        }
        return new Content(request.type(),label,values.values().stream().toList());
    }
    static String locale(String locale) {
        try { return ContentLocale.require(locale).code(); }
        catch(IllegalArgumentException invalid) { throw invalid("Unsupported document language."); }
    }
    static void id(long id) { if(id<1)throw invalid("A positive revision ID is required."); }
    static void version(Long version) { if(version==null || version<1)throw invalid("A positive edit version is required."); }
    static void page(int offset,int limit) { if(offset<0 || offset>1_000_000 || limit<1 || limit>50)throw invalid("Invalid document pagination."); }
    static String reason(String reason,boolean required) {
        String value=reason==null?"":reason.trim();
        if(value.length()>1000 || required && value.isBlank())throw invalid("Provide a reason of at most 1000 characters.");
        return value;
    }
    private static LegalProblem invalid(String message) { return new LegalProblem(Kind.INVALID,message); }
}
