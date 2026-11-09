package io.github.carloslimajlle.liquimanager.catalog;

import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.stream.ClusterBus;

/**
 * A rotation announced to one replica has to empty the secret cache of all of them: each keeps its own, for up to five minutes, and the
 * old credential would otherwise keep being handed out by the others until it expires by itself.
 */
@Component
class SecretCacheSync {

    /** The event the rotation announcement publishes on the {@link ClusterBus}. */
    static final String EVICT = "secrets.evict";

    SecretCacheSync(ClusterBus bus, SecretResolver resolver) {
        bus.addListener((name, data) -> {
            if (EVICT.equals(name)) {
                resolver.evictAll();
            }
        });
    }
}
