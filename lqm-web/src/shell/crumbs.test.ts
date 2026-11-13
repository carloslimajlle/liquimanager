import { describe, expect, it } from 'vitest';

import { crumb, crumbLabel, crumbTarget } from './crumbs';

describe('breadcrumb steps', () => {
  it('reads the label of a plain string and of a link', () => {
    expect(crumbLabel('Projetos')).toBe('Projetos');
    expect(crumbLabel(crumb('Projetos', '/projects'))).toBe('Projetos');
  });

  it('links a step that has a destination, and only while it is not the last one', () => {
    expect(crumbTarget(crumb('Projetos', '/projects'), false)).toBe('/projects');
    expect(crumbTarget(crumb('Projetos', '/projects'), true)).toBeNull();
  });

  it('never links a plain string', () => {
    expect(crumbTarget('gate', false)).toBeNull();
    expect(crumbTarget('gate', true)).toBeNull();
  });
});
