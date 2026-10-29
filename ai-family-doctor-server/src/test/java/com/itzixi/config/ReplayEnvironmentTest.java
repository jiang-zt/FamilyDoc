package com.itzixi.config;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import javax.sql.DataSource;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ReplayEnvironmentTest {
    MockEnvironment env() {
        var env=new MockEnvironment();env.setActiveProfiles("replay");
        return env.withProperty("server.address","127.0.0.1").withProperty("spring.datasource.username","family_replay")
            .withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/ai_family_doctor_replay")
            .withProperty("dashscope.mock.enabled","true").withProperty("chat.traffic-recording.enabled","false");
    }
    @Test void rejectsWrongDatabaseOrEnabledRecordingBeforeServing() throws Exception {
        var ds=mock(DataSource.class);var e=env().withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:3306/deepseek_doctor");
        assertThatThrownBy(()->new ReplayEnvironment(e,ds).verify()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new ReplayEnvironment(env().withProperty("chat.traffic-recording.enabled","true"),ds).verify()).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ds);
    }
    @Test void validatesActualCatalogAndExposesNoSecret() throws Exception {
        var ds=mock(DataSource.class);var conn=mock(Connection.class);var meta=mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(conn);when(conn.getCatalog()).thenReturn("ai_family_doctor_replay");
        when(conn.getMetaData()).thenReturn(meta);when(meta.getUserName()).thenReturn("family_replay@localhost");
        var e=new ReplayEnvironment(env(),ds);e.verify();
        assertThat(e.environment()).containsEntry("database","ai_family_doctor_replay").containsEntry("mock",true);
        assertThat(e.environment()).doesNotContainKeys("password","jwtSecret");
    }
}
