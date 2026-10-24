package node

import "context"

func newResourceOwnerForTest(ctx context.Context, admit resourceAdmission, lifecycle *Lifecycle, control sessionControl, cancel context.CancelFunc) resourceOwnerTestAPI {
	return NewResourceOwner(ctx, admit, lifecycle, control, cancel)
}
func joinResourceLifecycleForTest(l *Lifecycle, ctx context.Context) error { return l.JoinWorkers(ctx) }
