import { describe, expect, it } from 'vitest';

import { pt } from '../../i18n/pt';
import { describeEvent } from './SidePanels';

describe('describeEvent', () => {
  const actions = pt.access.side.audit.actions as Record<string, string>;
  const fallback = pt.access.side.audit.fallback;

  it('says what happened in words', () => {
    expect(describeEvent({ actor: 'ana', action: 'access.grant' }, actions, fallback)).toBe('ana concedeu um acesso');
    expect(describeEvent({ actor: 'bruno', action: 'exception.approve' }, actions, fallback)).toBe('bruno aprovou uma exceção');
  });

  it('shows the action code for one nobody wrote a sentence for, rather than hiding it', () => {
    expect(describeEvent({ actor: 'ana', action: 'something.new' }, actions, fallback)).toBe('ana: something.new');
  });
});
