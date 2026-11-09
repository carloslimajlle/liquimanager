import { describe, expect, it } from 'vitest';

import { pt } from '../../i18n/pt';
import { gateLabel, gateTone, severityEntries, severityLabel, severityTone } from './rules';

describe('severity', () => {
  it('maps each level to a tone that grows with the risk', () => {
    expect([0, 1, 2, 3, 4].map(severityTone)).toEqual(['neutral', 'minor', 'warning', 'danger', 'danger']);
  });

  it('names every level, so the badge never shows a bare number', () => {
    expect([0, 1, 2, 3, 4].map((s) => severityLabel(s, pt))).toEqual(['INFO', 'MINOR', 'MAJOR', 'CRITICAL', 'BLOCKER']);
  });
});

describe('gate', () => {
  it('colours the result and always has text, including "not evaluated"', () => {
    expect(gateTone('PASSED')).toBe('success');
    expect(gateTone('WARNED')).toBe('warning');
    expect(gateTone('BLOCKED')).toBe('danger');
    expect(gateTone(null)).toBe('neutral');
    expect(gateLabel('BLOCKED', pt)).toBe('bloqueado');
    expect(gateLabel(undefined, pt)).toBe('sem avaliação');
  });
});

describe('severityEntries', () => {
  it('lists the most severe first and leaves out the empty ones', () => {
    expect(severityEntries({ MAJOR: 1, CRITICAL: 2, INFO: 0 })).toEqual([['CRITICAL', 2], ['MAJOR', 1]]);
  });

  it('is empty for a clean gate', () => {
    expect(severityEntries({})).toEqual([]);
  });
});
