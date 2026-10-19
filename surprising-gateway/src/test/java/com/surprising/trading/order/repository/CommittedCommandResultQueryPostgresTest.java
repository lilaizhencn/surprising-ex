package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.service.StableOrderIdentity;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedCommandResultQueryPostgresTest {
    private JdbcTemplate jdbc;
    private AeronOrderProjectionRepository repository;
    private final UUID id = StableOrderIdentity.commandId(ProductLine.SPOT, 7, "lost-reply");

    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("CREATE SCHEMA receipt_query_it");
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=receipt_query_it",
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE core_command_result_projection (LIKE public.core_command_result_projection INCLUDING ALL)");
        repository = new AeronOrderProjectionRepository(jdbc);
        jdbc.update("""
                INSERT INTO core_command_result_projection(product_line,command_id,user_id,command_type,client_order_id,instrument_id,command_status,result_code,applied_command_count,cluster_position,occurred_at_epoch_ms) VALUES ('SPOT',?,7,'PLACE_ORDER','lost-reply','1',
                  'REJECTED','ORDER_NOT_FOUND',1,32,1700000000000)
                """, id);
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA receipt_query_it CASCADE"); }

    @Test void aCommittedRejectionWithoutAnOrderIsRecoveredByBothCommandAndClientIdentity() {
        assertThat(repository.commandResult(ProductLine.SPOT, 7, id)).satisfies(result -> {
            assertThat(result.commandId()).isEqualTo(id);
            assertThat(result.outcome()).isEqualTo("TERMINAL");
            assertThat(result.code()).isEqualTo("ORDER_NOT_FOUND");
        });
        assertThat(repository.submission(ProductLine.SPOT, 7, "lost-reply", id)).satisfies(result -> {
            assertThat(result.userId()).isEqualTo(7);
            assertThat(result.productLine()).isEqualTo(ProductLine.SPOT);
            assertThat(result.clientOrderId()).isEqualTo("lost-reply");
            assertThat(result.instrumentId()).isEqualTo("1");
            assertThat(result.outcome()).isEqualTo("TERMINAL");
        });
    }

    @Test void cancellationUsesACommittedCancelReceiptAndNeverInfersSuccessFromAnOrder() {
        var cancel = StableOrderIdentity.commandId(ProductLine.SPOT, 7, "cancel:91");
        assertThat(repository.cancellation(ProductLine.SPOT, 7, 91, cancel).outcome()).isEqualTo("RESULT_UNKNOWN");
        jdbc.update("""
                INSERT INTO core_command_result_projection(product_line,command_id,user_id,command_type,client_order_id,instrument_id,command_status,result_code,applied_command_count,cluster_position,occurred_at_epoch_ms) VALUES ('SPOT',?,7,'CANCEL_ORDER',null,null,
                  'APPLIED','NONE',2,64,1700000000001)
                """, cancel);
        assertThat(repository.cancellation(ProductLine.SPOT, 7, 91, cancel)).satisfies(result -> {
            assertThat(result.commandId()).isEqualTo(cancel);
            assertThat(result.prospectiveOrderIds()).containsExactly(91L);
            assertThat(result.outcome()).isEqualTo("TERMINAL");
            assertThat(result.code()).isEqualTo("NONE");
        });
        assertThat(repository.cancellation(ProductLine.SPOT, 8, 91, cancel).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.cancellation(ProductLine.OPTION, 7, 91, cancel).outcome()).isEqualTo("RESULT_UNKNOWN");
        jdbc.update("UPDATE core_command_result_projection SET command_type='PLACE_ORDER' WHERE command_id=?", cancel);
        assertThatThrownBy(() -> repository.cancellation(ProductLine.SPOT, 7, 91, cancel))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void amendmentQueriesOnlyTheCommittedOriginalRequestAndRejectsOtherCommandTypes() {
        var amend = StableOrderIdentity.replacementCommandId(ProductLine.SPOT, 7, "edit-91");
        assertThat(repository.amendment(ProductLine.SPOT, 7, amend).outcome()).isEqualTo("RESULT_UNKNOWN");
        jdbc.update("""
                INSERT INTO core_command_result_projection(product_line,command_id,user_id,command_type,client_order_id,instrument_id,command_status,result_code,applied_command_count,cluster_position,occurred_at_epoch_ms) VALUES ('SPOT',?,7,'AMEND_ORDER',null,null,
                  'APPLIED','NONE',3,96,1700000000002)
                """, amend);
        assertThat(repository.amendment(ProductLine.SPOT, 7, amend).code()).isEqualTo("NONE");
        assertThat(repository.amendment(ProductLine.SPOT, 8, amend).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.amendment(ProductLine.OPTION, 7, amend).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThatThrownBy(() -> repository.amendment(ProductLine.SPOT, 7, id)).isInstanceOf(IllegalStateException.class);
    }

    @Test void cancellationBatchReturnsCountsAndOriginalIdsOnlyFromCommittedItemFacts() {
        var key = StableOrderIdentity.commandId(ProductLine.SPOT, 7, "batch:cancel:plan-1");
        jdbc.update("""
                INSERT INTO core_command_result_projection(product_line,command_id,user_id,command_type,
                  command_status,result_code,applied_command_count,cluster_position,occurred_at_epoch_ms,cancel_batch_results)
                VALUES ('SPOT',?,7,'CANCEL_ORDER_BATCH','APPLIED','NONE',4,128,1700000000003,
                  '[{"index":0,"orderId":91,"success":true,"code":"NONE"},{"index":1,"orderId":92,"success":false,"code":"ORDER_NOT_FOUND"}]'::jsonb)
                """, key);
        var receipt = repository.cancellationBatch(ProductLine.SPOT, 7, key);
        assertThat(receipt.prospectiveOrderIds()).containsExactly(91L, 92L);
        var result = (com.surprising.trading.api.model.OrderBatchResponse) receipt.result();
        assertThat(result.requested()).isEqualTo(2); assertThat(result.completed()).isEqualTo(1); assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().get(1).message()).isEqualTo("ORDER_NOT_FOUND");
        assertThat(repository.cancellationBatch(ProductLine.SPOT, 8, key).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.cancellationBatch(ProductLine.OPTION, 7, key).outcome()).isEqualTo("RESULT_UNKNOWN");
        for (var malformed : List.of("[null]",
                "[{\"index\":3,\"orderId\":91,\"success\":true,\"code\":\"NONE\"}]",
                "[{\"index\":0,\"orderId\":91,\"success\":true,\"code\":\"ORDER_NOT_FOUND\"}]")) {
            jdbc.update("UPDATE core_command_result_projection SET cancel_batch_results=?::jsonb WHERE command_id=?", malformed, key);
            assertThatThrownBy(() -> repository.cancellationBatch(ProductLine.SPOT, 7, key))
                    .isInstanceOf(IllegalStateException.class);
        }
        jdbc.update("UPDATE core_command_result_projection SET cancel_batch_results=NULL WHERE command_id=?", key);
        assertThat(repository.cancellationBatch(ProductLine.SPOT, 7, key).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThatThrownBy(() -> repository.cancellationBatch(ProductLine.SPOT, 7, id)).isInstanceOf(IllegalStateException.class);
    }

    @Test void missingResultsAndDifferentUsersOrProductsRemainUnknown() {
        assertThat(repository.commandResult(ProductLine.SPOT, 8, id).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.commandResult(ProductLine.OPTION, 7, id).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThat(repository.submission(ProductLine.SPOT, 7, "new-request", UUID.randomUUID()).outcome()).isEqualTo("RESULT_UNKNOWN");
        assertThatThrownBy(() -> repository.submission(ProductLine.SPOT, 7, "different-key", id))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE core_command_result_projection SET result_code='NOT_A_CORE_CODE'");
        assertThatThrownBy(() -> repository.commandResult(ProductLine.SPOT, 7, id))
                .isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
    }
}
