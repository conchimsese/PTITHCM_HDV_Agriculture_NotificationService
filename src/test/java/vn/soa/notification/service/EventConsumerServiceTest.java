package vn.soa.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.soa.notification.entity.NotificationEntity;
import vn.soa.notification.entity.NotificationReceiptEntity;
import vn.soa.notification.entity.NotificationType;
import vn.soa.notification.event.EventEnvelope;
import vn.soa.notification.exception.EventIdReusedException;
import vn.soa.notification.exception.InvalidEventException;
import vn.soa.notification.repository.NotificationReceiptRepository;
import vn.soa.notification.repository.NotificationRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EventConsumerServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("notification_db")
            .withUsername("notification_app")
            .withPassword(UUID.randomUUID().toString());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", POSTGRES::getJdbcUrl);
        registry.add("DB_USERNAME", POSTGRES::getUsername);
        registry.add("DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    private EventConsumerService eventConsumerService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationReceiptRepository receiptRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void cleanUp() {
        notificationRepository.deleteAll();
        receiptRepository.deleteAll();
    }

    private EventEnvelope loadFixture(String fixtureName) throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/fixtures/" + fixtureName)) {
            if (is == null) {
                throw new IllegalArgumentException("Fixture not found: " + fixtureName);
            }
            return objectMapper.readValue(is, EventEnvelope.class);
        }
    }

    @Test
    void processAll10FixturesSuccessfully() throws Exception {
        String[] fixtures = {
                "offer_created.json",
                "offer_accepted.json",
                "message_created.json",
                "contract_created.json",
                "contract_status_changed.json",
                "deposit_paid.json",
                "deposit_status_changed.json",
                "reputation_changed.json",
                "account_locked.json",
                "account_unlocked.json"
        };

        for (String fixture : fixtures) {
            EventEnvelope envelope = loadFixture(fixture);
            eventConsumerService.processEvent(envelope);

            // Receipt must be created for every recipient
            for (UUID recipientId : envelope.recipientIds()) {
                assertThat(receiptRepository.findAll()).anyMatch(r ->
                        r.getId().getEventId().equals(envelope.eventId()) &&
                        r.getId().getRecipientId().equals(recipientId)
                );
            }

            // Notification must exist for non-suppressed events
            List<NotificationEntity> notis = notificationRepository.findAll().stream()
                    .filter(n -> n.getEventId().equals(envelope.eventId()))
                    .toList();

            assertThat(notis).isNotEmpty();
            assertThat(notis.get(0).getType().name()).isEqualTo(envelope.eventType());
        }
    }

    @Test
    void depositStatusChangedToLocked_SuppressesNotification_PersistsReceipt() throws Exception {
        EventEnvelope base = loadFixture("deposit_status_changed.json");
        // Create event with status LOCKED
        String lockedJson = """
                {
                  "eventId": "22222222-1111-4111-8111-111111111117",
                  "eventType": "DEPOSIT_STATUS_CHANGED",
                  "eventVersion": 1,
                  "sourceService": "payment-service",
                  "occurredAt": "2026-10-19T10:30:00Z",
                  "actorId": "22222222-2222-4222-8222-222222222222",
                  "recipientIds": ["33333333-3333-4333-8333-333333333333"],
                  "referenceType": "DEPOSIT",
                  "referenceId": "99999999-9999-4999-8999-999999999999",
                  "data": {
                    "depositId": "99999999-9999-4999-8999-999999999999",
                    "status": "LOCKED"
                  }
                }
                """;
        EventEnvelope lockedEvent = objectMapper.readValue(lockedJson, EventEnvelope.class);

        eventConsumerService.processEvent(lockedEvent);

        // Receipt should be persisted
        assertThat(receiptRepository.findAll()).anyMatch(r ->
                r.getId().getEventId().equals(lockedEvent.eventId())
        );

        // No notification should be created
        assertThat(notificationRepository.findAll()).noneMatch(n ->
                n.getEventId().equals(lockedEvent.eventId())
        );
    }

    @Test
    void duplicateEvent_IdempotentSuccess() throws Exception {
        EventEnvelope envelope = loadFixture("offer_created.json");

        // First process
        eventConsumerService.processEvent(envelope);
        long countNoti1 = notificationRepository.count();
        long countReceipt1 = receiptRepository.count();

        // Second process (duplicate) -> should complete cleanly without inserting extra rows
        eventConsumerService.processEvent(envelope);
        assertThat(notificationRepository.count()).isEqualTo(countNoti1);
        assertThat(receiptRepository.count()).isEqualTo(countReceipt1);
    }

    @Test
    void eventIdReused_WithDifferentPayload_ThrowsEventIdReusedException() throws Exception {
        EventEnvelope envelope1 = loadFixture("offer_created.json");
        eventConsumerService.processEvent(envelope1);

        // Same eventId, different referenceId/data
        String conflictingJson = """
                {
                  "eventId": "%s",
                  "eventType": "OFFER_CREATED",
                  "eventVersion": 1,
                  "sourceService": "trading-service",
                  "occurredAt": "2026-10-19T10:00:00Z",
                  "recipientIds": ["33333333-3333-4333-8333-333333333333"],
                  "referenceType": "OFFER",
                  "referenceId": "00000000-0000-0000-0000-000000000000",
                  "data": { "offerId": "different" }
                }
                """.formatted(envelope1.eventId());

        EventEnvelope envelope2 = objectMapper.readValue(conflictingJson, EventEnvelope.class);

        assertThatThrownBy(() -> eventConsumerService.processEvent(envelope2))
                .isInstanceOf(EventIdReusedException.class)
                .hasMessageContaining("EVENT_ID_REUSED");
    }

    @Test
    void invalidEnvelope_ThrowsInvalidEventException() {
        EventEnvelope invalid = new EventEnvelope(
                null, "OFFER_CREATED", 1, "trading-service",
                null, null, List.of(), null, null, null, null
        );

        assertThatThrownBy(() -> eventConsumerService.processEvent(invalid))
                .isInstanceOf(InvalidEventException.class);
    }
}
