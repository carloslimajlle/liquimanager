import { identityHeaders } from './client';

export type SseEvent = { name: string; data: string };

/**
 * Splits what has arrived of a text/event-stream into complete events. `carry` is whatever came after the last blank
 * line and is handed back on the next chunk: a network chunk can end in the middle of an event.
 */
export function parseSse(carry: string, chunk: string): { events: SseEvent[]; carry: string } {
  const text = (carry + chunk).replace(/\r\n/g, '\n');
  const blocks = text.split('\n\n');
  const rest = blocks.pop() ?? '';
  const events: SseEvent[] = [];
  for (const block of blocks) {
    let name = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (line === '' || line.startsWith(':')) continue; // a comment: the server's keep-alive
      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      const value = colon === -1 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'event') name = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length > 0 || name !== 'message') events.push({ name, data: data.join('\n') });
  }
  return { events, carry: rest };
}

const RETRY_MS = 3000;

/**
 * Reads an SSE endpoint with `fetch`, sending the caller's identity, and reconnects after a drop. Returns the function
 * that stops it.
 */
export function connectEventStream(
  url: string,
  handlers: { onEvent: (name: string, data: string) => void; onState: (connected: boolean) => void },
): () => void {
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function run() {
    try {
      const response = await fetch(url, {
        headers: { Accept: 'text/event-stream', ...identityHeaders() },
        signal: controller.signal,
      });
      if (!response.ok || !response.body) {
        throw new Error(String(response.status));
      }
      handlers.onState(true);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let carry = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        const parsed = parseSse(carry, decoder.decode(value, { stream: true }));
        carry = parsed.carry;
        parsed.events.forEach((event) => handlers.onEvent(event.name, event.data));
      }
    } catch {
      // fall through to the reconnect below; an abort is the caller leaving
    }
    if (!controller.signal.aborted) {
      handlers.onState(false);
      timer = setTimeout(run, RETRY_MS);
    }
  }

  void run();
  return () => {
    controller.abort();
    if (timer) clearTimeout(timer);
  };
}
