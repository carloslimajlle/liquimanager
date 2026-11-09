import { describe, expect, it } from 'vitest';

import { parseSse } from './sse';

describe('parseSse', () => {
  it('reads a named event with its data', () => {
    const { events, carry } = parseSse('', 'event: operation.ingested\ndata: {"id":"1"}\n\n');
    expect(events).toEqual([{ name: 'operation.ingested', data: '{"id":"1"}' }]);
    expect(carry).toBe('');
  });

  it('holds back an event that has not ended and finishes it with the next chunk', () => {
    const first = parseSse('', 'event: operation.ing');
    expect(first.events).toEqual([]);
    const second = parseSse(first.carry, 'ested\ndata: x\n\n');
    expect(second.events).toEqual([{ name: 'operation.ingested', data: 'x' }]);
  });

  it('reads several events from one chunk and keeps the unfinished tail', () => {
    const { events, carry } = parseSse('', 'event: a\ndata: 1\n\nevent: b\ndata: 2\n\nevent: c\ndata');
    expect(events.map((e) => e.name)).toEqual(['a', 'b']);
    expect(carry).toBe('event: c\ndata');
  });

  it('ignores comments (the keep-alive) and accepts CRLF line ends', () => {
    const { events } = parseSse('', ': keep-alive\r\n\r\nevent: a\r\ndata: 1\r\n\r\n');
    expect(events).toEqual([{ name: 'a', data: '1' }]);
  });

  it('joins the lines of a multi-line data field', () => {
    const { events } = parseSse('', 'data: one\ndata: two\n\n');
    expect(events).toEqual([{ name: 'message', data: 'one\ntwo' }]);
  });
});
