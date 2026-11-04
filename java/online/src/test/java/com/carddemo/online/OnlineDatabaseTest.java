package com.carddemo.online;

import com.carddemo.common.clock.FrozenClock;
import com.carddemo.online.account.AccountUpdateService;
import com.carddemo.online.commarea.CardDemoCommarea;
import com.carddemo.online.files.Account;
import com.carddemo.online.files.CardXref;
import com.carddemo.online.files.Customer;
import com.carddemo.online.files.FileResult;
import com.carddemo.common.testsupport.DbTest;
import com.carddemo.online.files.JdbcOnlineFiles;
import com.carddemo.online.files.OnlineDemoData;
import com.carddemo.online.files.OnlineFiles;
import com.carddemo.online.files.UserSecurity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DbTest
class OnlineDatabaseTest {
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateSeparateOnlineDatabase() throws Exception {
        String configuredUrl = System.getenv().getOrDefault("CARDEMO_DB_URL",
                "jdbc:postgresql://localhost:5432/carddemo_java?currentSchema=carddemo,public");
        String user = System.getenv().getOrDefault("CARDEMO_DB_USER", "carddemo");
        String password = System.getenv().getOrDefault("CARDEMO_DB_PASSWORD", "carddemo");
        String baseUrl = configuredUrl.split("\\?", 2)[0];
        String adminUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + "postgres";
        try (var connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            try (var databases = statement.executeQuery(
                    "SELECT 1 FROM pg_database WHERE datname='carddemo_online_test'")) {
                if (!databases.next()) statement.execute("CREATE DATABASE carddemo_online_test");
            }
        }
        String testUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1) + "carddemo_online_test";
        source = new DriverManagerDataSource(testUrl, user, password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("carddemo").defaultSchema("carddemo").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .schemas("carddemo").defaultSchema("carddemo").load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void jdbcSeedSaveAndRollbackUseOnlyTheDedicatedDatabase() {
        OnlineDemoData seed = new OnlineDemoData(jdbc);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM carddemo.account", Integer.class));
        seed.reset();
        JdbcOnlineFiles files = new JdbcOnlineFiles(jdbc);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM carddemo.account", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM carddemo.card_xref", Integer.class));
        assertEquals(0, files.readUserSec("USER0001").resp());
        assertEquals(0, files.readXrefByAcct("00000000001").resp());
        assertEquals("194.00", files.readAccount("00000000001").record().currBal().toPlainString());
        assertEquals(13, files.readAccount("00000000003").resp());
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        var service = new AccountUpdateService(files, FrozenClock.defaultClock(), transactions);
        var found = service.search("00000000001", new CardDemoCommarea()).session();
        var validated = service.update("00000000001", "ENTER", found,
                Map.of("creditLimit", "6000.00")).session();
        service.update("00000000001", "PF5", validated, Map.of());
        assertEquals("6000.00", files.readAccount("00000000001").record().creditLimit().toPlainString());
        seed.reset();
        OnlineFiles failCustomerRewrite = new OnlineFiles() {
            @Override
            public FileResult<UserSecurity> readUserSec(String id) {
                return files.readUserSec(id);
            }

            @Override
            public FileResult<CardXref> readXrefByAcct(String id) {
                return files.readXrefByAcct(id);
            }

            @Override
            public FileResult<Account> readAccount(String id) {
                return files.readAccount(id);
            }

            @Override
            public FileResult<Customer> readCustomer(String id) {
                return files.readCustomer(id);
            }

            @Override
            public FileResult<Account> readAccountForUpdate(String id) {
                return files.readAccountForUpdate(id);
            }

            @Override
            public FileResult<Customer> readCustomerForUpdate(String id) {
                return files.readCustomerForUpdate(id);
            }

            @Override
            public FileResult<Void> rewriteAccount(Account account) {
                return files.rewriteAccount(account);
            }

            @Override
            public FileResult<Void> rewriteCustomer(Customer customer) {
                return new FileResult<>(12, 0, null);
            }

            @Override
            public void syncpointRollback() {
                files.syncpointRollback();
            }
        };
        var failingService = new AccountUpdateService(failCustomerRewrite,
                FrozenClock.defaultClock(), transactions);
        var secondFound = failingService.search("00000000001", new CardDemoCommarea()).session();
        var secondValidated = failingService.update("00000000001", "ENTER", secondFound,
                Map.of("creditLimit", "7000.00")).session();
        failingService.update("00000000001", "PF5", secondValidated, Map.of());
        assertEquals("5000.00", files.readAccount("00000000001").record().creditLimit().toPlainString());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM carddemo.account", Integer.class));
    }
}
