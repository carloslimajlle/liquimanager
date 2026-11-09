import { useEffect, useRef, useState } from 'react';

import { connectEventStream } from './sse';

/**
 * Subscribes to the live feed of ingested operations (SSE, RNF-19: the dashboard has
 * to update in under 5 s without a reload).
 *
 * The stream is read with `fetch` and not `EventSource`, because `EventSource` cannot send an
 * `Authorization` header. It reconnects by itself when the connection drops, so the hook only has
 * to report whether it is connected right now: the screen shows "live" or "no live connection"
 * from that, instead of pretending to be live when it is not.
 *
 * `onEvent` is kept in a ref so passing a fresh closure on every render does not tear
 * the connection down and rebuild it.
 */
export function useOperationStream(onEvent: () => void): boolean {
  const [live, setLive] = useState(false);
  const handler = useRef(onEvent);

  useEffect(() => {
    handler.current = onEvent;
  });

  useEffect(() => {
    const stop = connectEventStream('/api/v1/stream/operations', {
      onEvent: (name) => {
        if (name === 'operation.ingested') handler.current();
      },
      onState: setLive,
    });
    return stop;
  }, []);

  return live;
}
