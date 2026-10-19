package vn.soa.notification.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import vn.soa.notification.event.EventEnvelope;
import vn.soa.notification.service.EventConsumerService;

@Component
public class NotificationKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaListener.class);

    private final EventConsumerService eventConsumerService;

    public NotificationKafkaListener(EventConsumerService eventConsumerService) {
        this.eventConsumerService = eventConsumerService;
    }

    @KafkaListener(
            topics = {
                    "soa.trading.offer.v1",
                    "soa.chat.message.v1",
                    "soa.contract.lifecycle.v1",
                    "soa.payment.deposit.v1",
                    "soa.user.reputation.v1",
                    "soa.user.account.v1"
            },
            groupId = "${spring.kafka.consumer.group-id:notification-service-v1}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onMessage(ConsumerRecord<String, EventEnvelope> record, Acknowledgment ack) {
        log.info("Received event: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());

        EventEnvelope envelope = record.value();
        eventConsumerService.processEvent(envelope);

        // Acknowledge after DB transaction commits successfully
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
