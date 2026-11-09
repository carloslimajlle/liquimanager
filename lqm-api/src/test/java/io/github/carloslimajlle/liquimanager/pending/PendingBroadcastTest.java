package io.github.carloslimajlle.liquimanager.pending;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.github.carloslimajlle.liquimanager.stream.OperationStream;

/** Screens learn at once that what waits for a decision changed, and only when it did. */
class PendingBroadcastTest {

    private final OperationStream stream = mock(OperationStream.class);
    private final PendingBroadcast broadcast = new PendingBroadcast(stream);

    private void finish(String method, int status, Exception failure) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        broadcast.afterCompletion(new MockHttpServletRequest(method, "/api/v1/promotions/1/approve"), response, new Object(), failure);
    }

    @Test
    @DisplayName("a decision that went through is announced")
    void announcesWhatChanged() {
        finish("POST", 200, null);

        verify(stream).broadcast("pending.changed");
    }

    @Test
    @DisplayName("a read, a refused request and a failed one are not announced: nothing changed")
    void staysQuietOtherwise() {
        finish("GET", 200, null);
        finish("POST", 403, null);
        finish("POST", 404, null);
        finish("POST", 200, new IllegalStateException("boom"));

        verify(stream, never()).broadcast("pending.changed");
    }
}
