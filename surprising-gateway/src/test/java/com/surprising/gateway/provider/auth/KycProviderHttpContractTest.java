package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import com.surprising.gateway.provider.auth.client.*;
import com.surprising.gateway.provider.auth.model.ComplianceModels.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class KycProviderHttpContractTest {
    final ObjectMapper json=new ObjectMapper();

    @Test void verifiedSumsubEvidenceBindsTheApplicantAndProvidesItsExpiryWithRetryableOutages() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var unavailable=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/resources/applicants/app-42/one",exchange->{
            if(unavailable.get()) { exchange.sendResponseHeaders(503,-1);exchange.close();return; }
            byte[] response="{\"id\":\"app-42\",\"externalUserId\":\"42\",\"info\":{\"idDocs\":[{\"idDocType\":\"PASSPORT\",\"validUntil\":\"2027-02-03\"}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var properties=new GatewayProperties();properties.getKyc().setSimulationEnabled(false);
            var config=properties.getKyc().getSumsub();config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
            config.setSecretKey("synthetic-api-secret");config.setWebhookSecret("synthetic-webhook-secret");
            byte[] payload="{\"type\":\"applicantReviewed\",\"reviewStatus\":\"completed\",\"reviewResult\":{\"reviewAnswer\":\"GREEN\"},\"applicantId\":\"app-42\",\"externalUserId\":\"42\",\"levelName\":\"basic-kyc-level\",\"createdAtMs\":\"2026-10-29 12:00:00.123\"}".getBytes(StandardCharsets.UTF_8);
            var headers=Map.of("x-payload-digest",SumsubKycProvider.hmac(config.getWebhookSecret(),"HmacSHA256",payload));
            var provider=new SumsubKycProvider(properties,json);
            assertThat(provider.verifyCallback(headers,payload).orElseThrow().evidenceExpiresAt()).isEqualTo(Instant.parse("2027-02-04T00:00:00Z"));
            unavailable.set(true);
            assertThatThrownBy(()->provider.verifyCallback(headers,payload)).isInstanceOf(IllegalStateException.class).hasMessageContaining("temporarily unavailable");
        } finally { server.stop(0); }
    }

    @Test void sumsubUsesDocumentedPathsCountryAndConfiguredLevelForCreationAndRefresh() throws Exception {
        var calls=Collections.synchronizedList(new ArrayList<String>());
        var bodies=Collections.synchronizedList(new ArrayList<tools.jackson.databind.JsonNode>());
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var properties=new GatewayProperties();properties.getKyc().setSimulationEnabled(false);
        var config=properties.getKyc().getSumsub();
        config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());
        config.setAppToken("synthetic-app-token");config.setSecretKey("synthetic-signing-secret");
        config.setLevelNames(Map.of("INDIVIDUAL_ENHANCED","enhanced-check","BUSINESS_STANDARD","company-check"));
        server.createContext("/",exchange->{
            byte[] body=exchange.getRequestBody().readAllBytes();String path=exchange.getRequestURI().toString();
            String expected=SumsubKycProvider.hmac(config.getSecretKey(),"HmacSHA256",
                    (exchange.getRequestHeaders().getFirst("X-App-Access-Ts")+exchange.getRequestMethod()+path+new String(body,StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
            if(!expected.equals(exchange.getRequestHeaders().getFirst("X-App-Access-Sig"))) { exchange.sendResponseHeaders(401,-1);exchange.close();return; }
            calls.add(exchange.getRequestMethod()+" "+path);
            if(body.length>0)bodies.add(json.readTree(body));
            String response=path.startsWith("/resources/accessTokens/sdk")?"{\"token\":\"synthetic-sdk\"}":"{\"id\":\"applicant-42\"}";
            if(exchange.getRequestMethod().equals("GET"))exchange.sendResponseHeaders(404,-1);
            else { byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes); }
            exchange.close();
        });server.start();
        try {
            var provider=new SumsubKycProvider(properties,json);
            var registry=new KycProviderRegistry(List.of(provider),properties);
            var request=new KycSubmissionRequest("INDIVIDUAL","ENHANCED","SG","PASSPORT","SUMSUB",null,null,"PENDING",List.of());
            var session=registry.start(42,request,List.of());
            assertThat(session.launchToken()).isEqualTo("synthetic-sdk");
            var profile=new KycProfile(42,"ENHANCED","PENDING","SG","PASSPORT","SUMSUB",session.providerReference(),null,null,null,null,Instant.now(),Instant.now(),"INDIVIDUAL","[]","PENDING");
            registry.refreshSession(profile,null);
            assertThat(calls).containsExactly("GET /resources/applicants/-;externalUserId=42/one",
                    "POST /resources/applicants?levelName=enhanced-check","POST /resources/accessTokens/sdk","POST /resources/accessTokens/sdk");
            assertThat(bodies.getFirst().path("fixedInfo").path("country").asString()).isEqualTo("SGP");
            assertThat(bodies.getFirst().path("type").asString()).isEqualTo("individual");
            assertThat(bodies.get(1).path("levelName").asString()).isEqualTo("enhanced-check");
            assertThat(bodies.get(2).path("levelName").asString()).isEqualTo("enhanced-check");
            assertThatThrownBy(()->registry.start(42,new KycSubmissionRequest("BUSINESS","ENHANCED","SG","PASSPORT","SUMSUB",null,null,"PENDING",List.of()),List.of())).hasMessageContaining("not supported");
            assertThat(calls).hasSize(4);
        } finally { server.stop(0); }
    }

    @Test void callbackRequiresSignedTimeAndTheRequestedFlowAndPreservesVeriffReasons() {
        var properties=new GatewayProperties();properties.getKyc().setSimulationEnabled(false);
        properties.getKyc().getSumsub().setWebhookSecret("synthetic-webhook-secret");
        var sumsub=new SumsubKycProvider(properties,json);
        byte[] payload="{\"type\":\"applicantReviewed\",\"reviewStatus\":\"onHold\",\"reviewResult\":{\"reviewAnswer\":\"GREEN\"},\"applicantId\":\"app-42\",\"externalUserId\":\"42\",\"levelName\":\"basic-kyc-level\",\"createdAtMs\":\"2026-10-29 12:00:00.123\"}".getBytes(StandardCharsets.UTF_8);
        var result=sumsub.verifyCallback(Map.of("x-payload-digest",SumsubKycProvider.hmac("synthetic-webhook-secret","HmacSHA256",payload)),payload).orElseThrow();
        assertThat(result.occurredAt()).isEqualTo(Instant.parse("2026-10-29T12:00:00.123Z"));
        var registry=new KycProviderRegistry(List.of(sumsub),properties);
        var profile=new KycProfile(42,"BASIC","PENDING","SG","PASSPORT","SUMSUB","app-42",null,null,null,null,Instant.now(),Instant.now());
        registry.requireCallbackLevel(profile,result);
        properties.getKyc().getSumsub().setLevelNames(Map.of("INDIVIDUAL_BASIC","other-check"));
        assertThatThrownBy(()->registry.requireCallbackLevel(profile,result)).hasMessageContaining("verification level");
        var veriffConfig=properties.getKyc().getVeriff();veriffConfig.setApiKey("synthetic-key");veriffConfig.setSharedSecret("synthetic-secret");
        byte[] veriff="{\"verification\":{\"id\":\"session-42\",\"vendorData\":\"42\",\"status\":\"resubmission_requested\",\"reason\":\"Upload a clearer document\",\"decisionTime\":\"2026-10-29T12:00:00Z\"}}".getBytes(StandardCharsets.UTF_8);
        var decision=new VeriffKycProvider(properties,json).verifyCallback(Map.of("x-auth-client","synthetic-key","x-hmac-signature",SumsubKycProvider.hmac("synthetic-secret","HmacSHA256",veriff)),veriff).orElseThrow();
        assertThat(decision.status()).isEqualTo("REJECTED");assertThat(decision.rejectionReason()).isEqualTo("Upload a clearer document");
    }
}
