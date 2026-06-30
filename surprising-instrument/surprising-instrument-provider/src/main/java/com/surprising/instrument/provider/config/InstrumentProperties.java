package com.surprising.instrument.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "surprising.instrument")
public class InstrumentProperties {

    private Kafka kafka = new Kafka();

    public Kafka getKafka() {
        return kafka;
    }

    public void setKafka(Kafka kafka) {
        this.kafka = kafka;
    }

    public static class Kafka {
        private String bootstrapServers = "localhost:9092";
        private String eventsTopic = "surprising.instrument.events.v1";

        public String getBootstrapServers() {
            return bootstrapServers;
        }

        public void setBootstrapServers(String bootstrapServers) {
            this.bootstrapServers = bootstrapServers;
        }

        public String getEventsTopic() {
            return eventsTopic;
        }

        public void setEventsTopic(String eventsTopic) {
            this.eventsTopic = eventsTopic;
        }
    }
}
