import type { PendingItem } from '../api/pending';
import type { Messages } from '../i18n';

/** One line saying what is waiting, shared by the bell and the banner. */
export function describePending(item: PendingItem, p: Messages['pending']): string {
  switch (item.kind) {
    case 'PROMOTION':
      return p.promotion(item.environment ?? '', item.pipeline ?? '', item.release ?? null);
    case 'ROLLBACK':
      return p.rollback(item.environment ?? '', item.changeset ?? null);
    case 'ACCESS':
      return p.access(item.requestedBy);
    default:
      return p.exception(item.check ?? '');
  }
}
