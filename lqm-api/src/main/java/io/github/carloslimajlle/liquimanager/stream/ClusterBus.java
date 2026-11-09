package io.github.carloslimajlle.liquimanager.stream;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What one API replica tells all of them: the live feed ({@code operation.ingested}), "the pending list changed" and "forget the cached
 * secrets". It rides on Postgres {@code LISTEN/NOTIFY}, because the database is already the one thing every replica shares, so nothing new
 * has to run next to it.
 *
 * <p>Every replica, the one that publishes included, hears an event through the database and delivers it to its own browsers; there is no
 * second, local path to double it. The one exception is a replica whose listener is down (the connection dropped and has not come back
 * yet): it delivers to its own subscribers directly, so a single replica keeps working through a database hiccup.
 *
 * <p>The listener holds a connection of its own, outside the pool, so the pool's connection lifetime never cuts it. If it drops it
 * reconnects by itself. An event is a hint, never the data (a notification is at most 8000 bytes and is lost to whoever is not listening):
 * screens that need certainty read the database, and the pending list also polls slowly as a net.
 */
@Component
public class ClusterBus {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterBus.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** The Postgres channel. */
    static final String CHANNEL = "lqm_events";
    private static final int MAX_PAYLOAD = 7500;
    private static final long RECONNECT_MS = 3000;

    private final JdbcClient jdbc;
    private final String url;
    private final String username;
    private final String password;
    private final List<BiConsumer<String, JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean connected;
    private Thread thread;

    ClusterBus(JdbcClient jdbc,
               @Value("${spring.datasource.url}") String url,
               @Value("${spring.datasource.username:}") String username,
               @Value("${spring.datasource.password:}") String password) {
        this.jdbc = jdbc;
        this.url = url;
        this.username = username;
        this.password = password;
    }

    /** Called with the name and data of every event, on the listener thread: keep it quick and never throw. */
    public void addListener(BiConsumer<String, JsonNode> listener) {
        listeners.add(listener);
    }

    @PostConstruct
    void start() {
        running = true;
        thread = new Thread(this::listen, "lqm-cluster-bus");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** Whether the listener is attached right now (for the health of the replica and for tests). */
    boolean connected() {
        return connected;
    }

    /** Announces an event to every replica. The payload is a hint: when it does not fit in a notification it goes without data. */
    public void publish(String name, Object data) {
        String payload;
        try {
            payload = JSON.writeValueAsString(Map.of("name", name, "data", data == null ? Map.of() : data));
            if (payload.length() > MAX_PAYLOAD) {
                payload = JSON.writeValueAsString(Map.of("name", name, "data", Map.of()));
            }
        } catch (RuntimeException e) {
            LOG.warn("could not encode the event {}: {}", name, e.toString());
            return;
        }
        try {
            jdbc.sql("select pg_notify(:channel, :payload)").param("channel", CHANNEL).param("payload", payload).query().singleRow();
        } catch (RuntimeException e) {
            LOG.warn("could not announce the event {}: {}", name, e.toString());
            dispatch(payload);
            return;
        }
        if (!connected) {
            // This replica will not hear its own notification: tell its own browsers directly.
            dispatch(payload);
        }
    }

    private void listen() {
        while (running) {
            try (Connection connection = DriverManager.getConnection(url, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("LISTEN " + CHANNEL);
                PGConnection pg = connection.unwrap(PGConnection.class);
                connected = true;
                LOG.info("cluster bus listening on {}", CHANNEL);
                while (running) {
                    PGNotification[] received = pg.getNotifications(5000);
                    if (received != null) {
                        for (PGNotification notification : received) {
                            dispatch(notification.getParameter());
                        }
                    } else if (!connection.isValid(2)) {
                        break;
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    LOG.warn("cluster bus listener lost: {}", e.toString());
                }
            } finally {
                connected = false;
            }
            if (running) {
                try {
                    Thread.sleep(RECONNECT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void dispatch(String payload) {
        JsonNode event;
        try {
            event = JSON.readTree(payload);
        } catch (RuntimeException e) {
            LOG.debug("ignored a notification that is not JSON");
            return;
        }
        String name = event.path("name").asString(null);
        if (name == null) {
            return;
        }
        JsonNode data = event.path("data");
        for (BiConsumer<String, JsonNode> listener : listeners) {
            try {
                listener.accept(name, data);
            } catch (RuntimeException e) {
                LOG.warn("a listener of {} failed: {}", name, e.toString());
            }
        }
    }
}
