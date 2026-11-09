import { describe, expect, it } from 'vitest';

import { messagesFor } from '../../i18n';
import { executorLabel, isFailure, pageRange, pageWindow, statusTone } from './rules';

const t = messagesFor('pt-BR');

describe('executorLabel', () => {
  it('uses a fixed label for automated sources, whoever the user was', () => {
    expect(executorLabel({ source: 'pipeline', executedBy: 'p.ricardo' }, t)).toBe('pipeline');
    expect(executorLabel({ source: 'startup', executedBy: 'gate' }, t)).toBe('startup (spring)');
    expect(executorLabel({ source: 'scheduler', executedBy: 'lqm-worker' }, t)).toBe('scheduler');
    expect(executorLabel({ source: 'scanner', executedBy: 'lqm-worker' }, t)).toBe('scanner');
  });

  it('shows the person when a human ran it', () => {
    expect(executorLabel({ source: 'cli-local', executedBy: 'l.martins' }, t)).toBe('l.martins');
    expect(executorLabel({ source: 'ui', executedBy: 'ana.souza' }, t)).toBe('ana.souza');
  });
});

describe('statusTone / isFailure', () => {
  it('treats failure and block alike: both are red and both tint the row', () => {
    expect(statusTone('FAILURE')).toBe('danger');
    expect(statusTone('BLOCKED')).toBe('danger');
    expect(isFailure('FAILURE')).toBe(true);
    expect(isFailure('BLOCKED')).toBe(true);
  });

  it('does not tint a running or successful row', () => {
    expect(statusTone('SUCCESS')).toBe('success');
    expect(isFailure('SUCCESS')).toBe(false);
    expect(isFailure('RUNNING')).toBe(false);
  });
});

describe('pageRange', () => {
  it('gives a 1-based range for a full and for a partial last page', () => {
    expect(pageRange(0, 25, 1284, 25)).toEqual({ from: 1, to: 25 });
    expect(pageRange(51, 25, 1284, 9)).toEqual({ from: 1276, to: 1284 });
  });

  it('has no range for an empty set', () => {
    expect(pageRange(0, 25, 0, 0)).toEqual({ from: 0, to: 0 });
  });
});

describe('pageWindow', () => {
  it('collapses the middle of a long list into a gap', () => {
    expect(pageWindow(0, 107)).toEqual([0, 1, null, 106]);
    expect(pageWindow(50, 107)).toEqual([0, null, 49, 50, 51, null, 106]);
    expect(pageWindow(106, 107)).toEqual([0, null, 105, 106]);
  });

  it('shows every page when there are few, and none when there are none', () => {
    expect(pageWindow(1, 3)).toEqual([0, 1, 2]);
    expect(pageWindow(0, 1)).toEqual([0]);
    expect(pageWindow(0, 0)).toEqual([]);
  });
});
