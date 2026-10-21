package com.surprising.gateway.c2c;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cProfilesTransactionPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();}
    @AfterEach void cleanup() {if(fixture!=null) fixture.close();}

    @Test void failedGrantCleanupRollsBackSettingsButRetainsConsumedVerification() throws Exception {
        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,
                new C2cMeController.Settings("1","QA Buyer",false,5,new C2cVerification("123456","")),200);
        fixture.bean(C2cAccess.class).verifyFunds(C2cHttpFixture.principal(12),"Bearer qa-12","123456","");
        fixture.db.jdbc.execute("CREATE TABLE c2c_verification_consumptions(consumed INTEGER NOT NULL)");
        fixture.db.jdbc.update("INSERT INTO c2c_verification_consumptions VALUES(0)");
        when(fixture.security.verify(anyLong(),anyString(),any(),any(),any())).thenAnswer(call -> {
            fixture.db.jdbc.update("UPDATE c2c_verification_consumptions SET consumed=consumed+1");return true;
        });
        fixture.db.jdbc.execute("""
                CREATE FUNCTION reject_grant_cleanup() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic grant cleanup failure' USING ERRCODE='23514'; END $$
                """);
        fixture.db.jdbc.execute("CREATE TRIGGER reject_grant_cleanup BEFORE DELETE ON c2c_security_grants FOR EACH ROW EXECUTE FUNCTION reject_grant_cleanup()");
        var change=new C2cMeController.Settings("2","QA Changed",true,15,new C2cVerification("123456",""));

        fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,change,409);

        assertThat(fixture.db.jdbc.queryForMap("SELECT revision,verification_minutes,nickname,receive_strangers FROM c2c_profiles WHERE user_id=12"))
                .containsEntry("revision",2L).containsEntry("verification_minutes",5).containsEntry("nickname","QA Buyer").containsEntry("receive_strangers",false);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants WHERE user_id=12",Long.class)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT consumed FROM c2c_verification_consumptions",Integer.class)).isEqualTo(1);
        fixture.db.jdbc.execute("DROP TRIGGER reject_grant_cleanup ON c2c_security_grants");
        assertThat(fixture.call(HttpMethod.PUT,"/api/v1/c2c/me",12,change,200).get("revision")).isEqualTo("3");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_security_grants WHERE user_id=12",Long.class)).isZero();
        verifyNoInteractions(fixture.core);
    }

    @Test void notificationCursorAndReadThroughStayWithinTheCurrentOwner() throws Exception {
        for(int i=0;i<3;i++) fixture.db.jdbc.update("""
                INSERT INTO c2c_notifications(user_id,kind,entity_id,details) VALUES(12,'PRICE_ALERT',?,?::jsonb)
                """,UUID.randomUUID(),"{\"targetPrice\":\"6.65\",\"sequence\":\""+i+"\"}");
        fixture.db.jdbc.update("INSERT INTO c2c_notifications(user_id,kind,entity_id,details) VALUES(13,'PRICE_ALERT',?,'{}')",UUID.randomUUID());
        var page=fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications?limit=2",12,null,200);
        assertThat(page.get("hasMore")).isEqualTo(true);assertThat(page.get("unreadCount")).isEqualTo("3");
        assertThat((List<?>)page.get("items")).hasSize(2);
        String cursor=(String)page.get("nextBeforeId");
        var last=fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications?limit=2&beforeId="+cursor,12,null,200);
        assertThat(last.get("hasMore")).isEqualTo(false);assertThat(last.get("nextBeforeId")).isNull();
        var item=(Map<?,?>)((List<?>)last.get("items")).getFirst();
        assertThat(((Map<?,?>)item.get("details")).get("targetPrice")).isEqualTo("6.65");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/notifications/read",12,new C2cMeController.ReadNotifications(cursor),200);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications",12,null,200).get("unreadCount")).isEqualTo("1");
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/me/notifications",13,null,200).get("unreadCount")).isEqualTo("1");
    }

    @Test void identicalReportCanReplayAtTheDailyLimitButANewReportCannotBypassIt() throws Exception {
        UUID original=UUID.randomUUID();
        for(int i=0;i<20;i++) fixture.db.jdbc.update("INSERT INTO c2c_reports(report_id,user_id,target_user_id,reason) VALUES(?,12,11,'Synthetic quota report')",i==0?original:UUID.randomUUID());
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/reports",12,new C2cMeController.Report(original,"11",null,"Synthetic quota report"),200);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/me/reports",12,new C2cMeController.Report(UUID.randomUUID(),"11",null,"Synthetic quota report"),409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_reports WHERE user_id=12",Long.class)).isEqualTo(20);
    }
}
