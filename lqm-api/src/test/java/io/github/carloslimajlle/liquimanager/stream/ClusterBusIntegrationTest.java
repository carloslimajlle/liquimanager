package io.github.carloslimajlle.liquimanager.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;

/**
 * Two replicas of the API, played by two buses on the same database: what one announces, the other hears, and the one that announced
 * hears it once too (not twice: there is no local shortcut while its listener is attached).
 */
class ClusterBusIntegrationTest extends AbstractDatabaseTest {

    @Autowired
    private ClusterBus replicaA;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private Environment environment;

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("an event published on one replica reaches the other, with its data, and the publisher hears it once")
    void reachesTheOtherReplica() throws Exception {
        ClusterBus replicaB = new ClusterBus(jdbc, environment.getProperty("spring.datasource.url"),
                environment.getProperty("spring.datasource.username", ""), environment.getProperty("spring.datasource.password", ""));
        List<String> heardByB = new CopyOnWriteArrayList<>();
        List<String> heardByA = new CopyOnWriteArrayList<>();
        replicaB.addListener((name, data) -> heardByB.add(name + ":" + data.path("project").asString("")));
        replicaA.addListener((name, data) -> heardByA.add(name + ":" + data.path("project").asString("")));
        replicaB.start();
        try {
            await(replicaB::connected, "the second replica to listen");
            await(replicaA::connected, "the first replica to listen");

            replicaA.publish("test.event", Map.of("project", "gate"));

            await(() -> heardByB.contains("test.event:gate"), "the other replica to hear the event");
            await(() -> heardByA.contains("test.event:gate"), "the publisher to hear its own event");
            Thread.sleep(300);
            assertThat(heardByA.stream().filter("test.event:gate"::equals).count()).as("once, not twice").isEqualTo(1);
            assertThat(heardByB.stream().filter("test.event:gate"::equals).count()).isEqualTo(1);
        } finally {
            replicaB.stop();
        }
    }

    @Test
    @DisplayName("a payload too big for a notification goes without its data instead of being lost")
    void oversizedPayload() throws Exception {
        List<String> heard = new CopyOnWriteArrayList<>();
        replicaA.addListener((name, data) -> {
            if ("test.big".equals(name)) {
                heard.add(data.toString());
            }
        });
        await(replicaA::connected, "the replica to listen");

        replicaA.publish("test.big", Map.of("blob", "x".repeat(9000)));

        await(() -> !heard.isEmpty(), "the event");
        assertThat(heard.get(0)).isEqualTo("{}");
    }
}
