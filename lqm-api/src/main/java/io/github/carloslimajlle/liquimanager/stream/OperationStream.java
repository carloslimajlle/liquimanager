package io.github.carloslimajlle.liquimanager.stream;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.http.MediaType;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;
import tools.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import io.github.carloslimajlle.liquimanager.access.ReadScope;

/**
 * Fans out {@code operation.ingested} events to every connected browser (RNF-19: the
 * dashboard's live table updates in under 5 s). Server-Sent Events rather than
 * WebSocket (ADR-005): the traffic is one-directional.
 *
 * <p>Two details that look optional and are not:
 * <ul>
 *   <li>A comment is sent the moment a client subscribes. Without it the response
 *       headers are not flushed until the first real event, so the browser's
 *       {@code EventSource} never fires {@code onopen} and the screen believes it is
 *       disconnected — for as long as nothing is being ingested.
 *   <li>A keep-alive comment goes out every 20 s. An idle stream is otherwise closed by
 *       proxies and load balancers, and the client only notices when it next reconnects.
 * </ul>
 *
 * <p>Each replica serves its own subscribers, and the events reach all of them through
 * {@link ClusterBus} (Postgres LISTEN/NOTIFY): an ingest that lands on replica A is
 * delivered to the browsers connected to replica B as well. Publishing never delivers
 * directly; every replica, the publisher too, delivers what it hears from the bus.
 */
@Component
public class OperationStream {

    private static final Logger LOG = LoggerFactory.getLogger(OperationStream.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long KEEP_ALIVE_SECONDS = 20;

    private final ClusterBus bus;
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    /** What each subscriber may read: an operation is sent only to the ones whose scope covers its connection. */
    private final java.util.Map<SseEmitter, ReadScope.Visible> visibility = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledExecutorService keepAlive = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lqm-sse-keepalive");
        thread.setDaemon(true);
        return thread;
    });

    OperationStream(ClusterBus bus) {
        this.bus = bus;
        bus.addListener(this::deliver);
    }

    @PostConstruct
    void startKeepAlive() {
        keepAlive.scheduleAtFixedRate(this::ping, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stopKeepAlive() {
        keepAlive.shutdownNow();
    }

    /**
     * Ends every open stream as soon as the context starts closing.
     *
     * <p>Spring's graceful shutdown waits for in-flight requests, and a stream never ends
     * on its own, so without this every deploy would hold the pod for the whole grace
     * period (30 s) before it could exit. It must run on ContextClosedEvent, which fires
     * BEFORE the web server begins draining; a @PreDestroy would be too late. Browsers
     * reconnect by themselves, to whichever replica is up.
     */
    @EventListener(ContextClosedEvent.class)
    void closeStreams() {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.complete();
            } catch (RuntimeException e) {
                LOG.debug("stream already closed while shutting down");
            }
        }
        subscribers.clear();
        visibility.clear();
    }

    public SseEmitter subscribe() {
        return subscribe(ReadScope.Visible.everything());
    }

    public SseEmitter subscribe(ReadScope.Visible visible) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.add(emitter);
        visibility.put(emitter, visible);
        emitter.onCompletion(() -> forget(emitter));
        emitter.onTimeout(() -> forget(emitter));
        emitter.onError(e -> forget(emitter));
        send(emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    private void forget(SseEmitter emitter) {
        subscribers.remove(emitter);
        visibility.remove(emitter);
    }

    int subscriberCount() {
        return subscribers.size();
    }

    void ping() {
        for (SseEmitter emitter : subscribers) {
            send(emitter, SseEmitter.event().comment("keep-alive"));
        }
    }

    /** Only after commit: the feed must never announce an operation the database does not hold. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onIngested(OperationIngested event) {
        bus.publish("operation.ingested", event);
    }

    /** What the bus brought, from this replica or another: to every subscriber that may read it. */
    void deliver(String name, JsonNode data) {
        String json = data.toString();
        UUID connection = null;
        if ("operation.ingested".equals(name)) {
            String id = data.path("connectionId").asString(null);
            connection = id == null ? null : UUID.fromString(id);
        }
        for (SseEmitter emitter : subscribers) {
            if ("operation.ingested".equals(name)) {
                ReadScope.Visible visible = visibility.getOrDefault(emitter, ReadScope.Visible.everything());
                if (connection == null ? !visible.all() : !visible.connection(connection)) {
                    continue;
                }
            }
            send(emitter, SseEmitter.event().name(name).data(json, MediaType.APPLICATION_JSON));
        }
    }

    /**
     * Tells every connected browser that something changed, with no payload: what changed depends on who is asking (the pending list
     * is per person), so each client asks again for its own view. Called after the change is committed.
     */
    public void broadcast(String name) {
        bus.publish(name, Map.of());
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            forget(emitter);
            LOG.debug("dropped a closed SSE subscriber");
        }
    }
}
