package com.flowpay.backend.webhook.infrastructure.security;

import com.flowpay.backend.ledger.domain.LedgerAccountCode;
import com.flowpay.backend.payment.application.event.PaymentSucceededEventV1;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Currency;

/** Test-only V011 database fixture. Never shipped in the executable application. */
public final class StagingPhaseSevenFixture {
    private static final Instant OCCURRED = Instant.parse("2026-10-01T00:00:00Z");

    private StagingPhaseSevenFixture() { }

    public static void main(String[] arguments) {
        try {
            if (!"synthetic-only".equals(System.getenv("FLOWPAY_STAGE_DISPOSABLE"))) {
                throw new IllegalStateException("Disposable fixture marker required");
            }
            Class.forName("org.postgresql.Driver");
            Path secrets = Path.of("/run/secrets");
            seed(System.getenv("DB_URL"), "flowpay_stage", Files.readString(secrets.resolve("DB_PASSWORD")).trim(),
                    Base64.getDecoder().decode(Files.readString(secrets.resolve("WEBHOOK_SECRET_ENCRYPTION_KEY")).trim()),
                    "whsec_" + Files.readString(secrets.resolve("LEGACY_WEBHOOK_SECRET")).trim());
            System.out.println("PASS: isolated Phase Seven database initialized");
        } catch (Exception exception) {
            // No values, SQL, parser causes, credentials or ciphertext diagnostics.
            System.err.println("FAIL: isolated Phase Seven database fixture rejected");
            System.exit(1);
        }
    }

    static void seed(String url, String user, String password, byte[] key, String signingSecret) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'")) {
            rows.next();
            if (rows.getLong(1) != 0) throw new IllegalStateException("Fixture requires a fresh empty database");
        }
        Flyway.configure().dataSource(url, user, password).target("11").load().migrate();
        var cipher = new AesGcmWebhookSecretCipher(new SecretKeySpec(key, "AES"));
        String encrypted = cipher.encrypt(signingSecret);
        var mapper = new ObjectMapper();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            long merchant = insert(connection, """
                    INSERT INTO merchants(public_id,name,status,created_at,updated_at)
                    VALUES ('mrc_staging_legacy','Owned legacy fixture','ACTIVE',?,?) RETURNING id
                    """, OCCURRED, OCCURRED);
            long payment = insert(connection, """
                    INSERT INTO payment_intents(public_id,merchant_id,amount_minor,currency,status,created_at,updated_at)
                    VALUES ('pi_staging_legacy',?,123000,'VND','SUCCEEDED',?,?) RETURNING id
                    """, merchant, OCCURRED, OCCURRED);
            insert(connection, """
                    INSERT INTO payment_transactions(public_id,payment_intent_id,attempt_no,provider,
                        provider_transaction_id,status,started_at,completed_at,created_at,updated_at)
                    VALUES ('ptxn_staging_legacy',?,1,'SIMULATOR','sim_legacy','SUCCEEDED',?,?,?,?) RETURNING id
                    """, payment, OCCURRED, OCCURRED, OCCURRED, OCCURRED);
            long clearing = insert(connection, """
                    INSERT INTO ledger_accounts(public_id,account_code,account_type,owner_type,currency,status,created_at)
                    VALUES ('la_staging_clearing',?,'SYSTEM_CLEARING','SYSTEM','VND','ACTIVE',?) RETURNING id
                    """, LedgerAccountCode.systemClearing(Currency.getInstance("VND")).value(), OCCURRED);
            long payable = insert(connection, """
                    INSERT INTO ledger_accounts(public_id,account_code,account_type,owner_type,owner_id,currency,status,created_at)
                    VALUES ('la_staging_payable',?,'MERCHANT_PAYABLE','MERCHANT',?,'VND','ACTIVE',?) RETURNING id
                    """, LedgerAccountCode.merchantPayable(merchant, Currency.getInstance("VND")).value(), merchant, OCCURRED);
            long posting = insert(connection, """
                    INSERT INTO ledger_transactions(public_id,posting_type,reference_type,reference_id,currency,occurred_at,created_at)
                    VALUES ('ltxn_staging_legacy','PAYMENT_SUCCEEDED','PAYMENT_INTENT','pi_staging_legacy','VND',?,?) RETURNING id
                    """, OCCURRED, OCCURRED);
            insert(connection, """
                    INSERT INTO ledger_entries(ledger_transaction_id,ledger_account_id,entry_no,direction,amount_minor,created_at)
                    VALUES (?,?,1,'DEBIT',123000,?) RETURNING id
                    """, posting, clearing, OCCURRED);
            insert(connection, """
                    INSERT INTO ledger_entries(ledger_transaction_id,ledger_account_id,entry_no,direction,amount_minor,created_at)
                    VALUES (?,?,2,'CREDIT',123000,?) RETURNING id
                    """, posting, payable, OCCURRED);
            long endpoint = insert(connection, """
                    INSERT INTO webhook_endpoints(public_id,merchant_id,url,secret_ciphertext,status,created_at,updated_at)
                    VALUES ('wep_staging_legacy',?,'https://11.203.0.10:8443/legacy',?,'ACTIVE',?,?) RETURNING id
                    """, merchant, encrypted, OCCURRED, OCCURRED);
            try (var statement = connection.prepareStatement("INSERT INTO webhook_endpoint_events VALUES (?,'payment.succeeded')")) {
                statement.setLong(1, endpoint);
                statement.executeUpdate();
            }
            var paymentData = mapper.createObjectNode().put("id", "pi_staging_legacy").put("amount", 123000)
                    .put("currency", "VND").put("status", "SUCCEEDED").putNull("failureCode").putNull("failureMessage");
            var data = mapper.createObjectNode();
            data.set("payment", paymentData);
            var publicPayload = mapper.createObjectNode().put("id", "evt_staging_legacy").put("type", "payment.succeeded")
                    .put("createdAt", OCCURRED.toString());
            publicPayload.set("data", data);
            long event = insert(connection, """
                    INSERT INTO webhook_events(public_id,source_event_id,merchant_id,event_type,resource_type,resource_id,payload,occurred_at,created_at)
                    VALUES ('evt_staging_legacy','evt_staging_legacy_source',?,'payment.succeeded','PAYMENT_INTENT',
                        'pi_staging_legacy',CAST(? AS jsonb),?,?) RETURNING id
                    """, merchant, publicPayload.toString(), OCCURRED, OCCURRED);
            insert(connection, """
                    INSERT INTO webhook_deliveries(public_id,webhook_event_id,webhook_endpoint_id,status,next_attempt_at,created_at,updated_at)
                    VALUES ('wdl_staging_legacy',?,?,'PENDING',?,?,?) RETURNING id
                    """, event, endpoint, OCCURRED, OCCURRED, OCCURRED);
            String source = mapper.writeValueAsString(new PaymentSucceededEventV1(merchant, "pi_staging_legacy", 123000, "VND", OCCURRED));
            insert(connection, """
                    INSERT INTO outbox_events(event_id,aggregate_type,aggregate_id,event_type,payload,status,occurred_at,
                        available_at,published_at,created_at)
                    VALUES ('evt_staging_legacy_source','PAYMENT_INTENT','pi_staging_legacy','payment.succeeded.v1',
                        CAST(? AS jsonb),'PUBLISHED',?,?,?,?) RETURNING id
                    """, source, OCCURRED, OCCURRED, OCCURRED, OCCURRED);
            connection.commit();
        }
    }

    private static long insert(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                Object value = parameters[index];
                statement.setObject(index + 1, value instanceof Instant instant ? instant.atOffset(java.time.ZoneOffset.UTC) : value);
            }
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("Missing fixture row");
                return rows.getLong(1);
            }
        }
    }
}
