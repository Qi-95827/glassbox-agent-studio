import { describe, expect, it } from 'vitest';
import { deliveryActiveStep, isLocalCommit } from './DeliveryStepper';

describe('Git delivery progress', () => {
  it('审查拒绝后停在审批步骤，不被旧提交或推送推进', () => {
    expect(
      deliveryActiveStep({
        diffReviewed: true,
        approvalStatus: 'REJECTED',
        committed: true,
        deliveryStatus: 'CONFIRMED'
      })
    ).toBe(1);
    expect(
      deliveryActiveStep({
        diffReviewed: true,
        approvalStatus: 'STALE',
        committed: true,
        deliveryStatus: 'CONFIRMED'
      })
    ).toBe(1);
  });

  it('批准后按本地提交和推送记录分别推进', () => {
    expect(
      deliveryActiveStep({
        diffReviewed: true,
        approvalStatus: 'APPROVED',
        committed: false,
        deliveryStatus: null
      })
    ).toBe(2);
    expect(
      deliveryActiveStep({
        diffReviewed: true,
        approvalStatus: 'APPROVED',
        committed: true,
        deliveryStatus: null
      })
    ).toBe(3);
    expect(
      deliveryActiveStep({
        diffReviewed: true,
        approvalStatus: 'APPROVED',
        committed: true,
        deliveryStatus: 'CONFIRMED'
      })
    ).toBe(4);
  });

  it('本地提交步骤排除推送/合并记录', () => {
    expect(isLocalCommit({ kind: 'LOCAL_COMMIT' })).toBe(true);
    expect(isLocalCommit({ kind: 'PUSH' })).toBe(false);
    expect(isLocalCommit({ kind: 'MERGE' })).toBe(false);
    expect(isLocalCommit({})).toBe(true);
  });
});
