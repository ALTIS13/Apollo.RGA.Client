package node

import (
	"context"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

const maxCarrierResources = 64

type carrierResource struct {
	end    time.Time // original Home NotAfter; never renewed by resource cleanup
	active bool
}

// ResourceOwner retires one fixed-QA carrier generation, not a public worker
// pool. It grants nothing and does not equate Run return with provider leave.
type ResourceOwner struct {
	mu         sync.Mutex
	ctx        context.Context
	admit      func(context.Context, string, map[string]any) (personal.NodeLease, error)
	lifecycle  *Lifecycle
	control    sessionControl
	cancel     context.CancelFunc
	resources  map[string]carrierResource
	pending    int
	operations int
	armed      bool
	closing    bool
	fenced     bool
	changed    chan struct{}
	done       chan struct{}
}

func NewResourceOwner(ctx context.Context, admit func(context.Context, string, map[string]any) (personal.NodeLease, error), lifecycle *Lifecycle, control sessionControl, cancel context.CancelFunc) *ResourceOwner {
	o := &ResourceOwner{ctx: ctx, admit: admit, lifecycle: lifecycle, control: control, cancel: cancel,
		resources: make(map[string]carrierResource), changed: make(chan struct{}), done: make(chan struct{})}
	go o.expireReservations()
	return o
}

func (o *ResourceOwner) signalLocked() { close(o.changed); o.changed = make(chan struct{}) }

func (o *ResourceOwner) retireLocked() bool {
	if !o.closing && o.armed && o.pending == 0 && len(o.resources) == 0 {
		o.closing = true
		o.signalLocked()
		return true
	}
	return false
}

func (o *ResourceOwner) Admit(ctx context.Context, deviceID string, claims map[string]any) (personal.NodeLease, error) {
	o.mu.Lock()
	if ctx == nil || ctx.Err() != nil || o.ctx.Err() != nil || o.closing || o.pending+len(o.resources) >= maxCarrierResources {
		o.mu.Unlock()
		return personal.NodeLease{}, personal.ErrNodeUnavailable
	}
	o.pending++ // registered before the Home call, including a held call
	o.signalLocked()
	o.mu.Unlock()
	lease, err := o.admit(ctx, deviceID, claims)
	o.mu.Lock()
	o.pending--
	if err == nil {
		_, duplicate := o.resources[lease.SessionID]
		if lease.SessionID == "" || duplicate {
			err = personal.ErrNodeDenied
		} else {
			o.armed = true
			if o.closing || o.ctx.Err() != nil || !time.Now().Before(lease.NotAfter) {
				// The trusted Home call issued this exact reservation even if
				// native can no longer publish it. Record terminal, never grant.
				o.lifecycle.CloseReservation(lease.SessionID)
				err = personal.ErrNodeUnavailable
			} else {
				o.resources[lease.SessionID] = carrierResource{end: lease.NotAfter}
			}
		}
	}
	retire := o.retireLocked()
	o.signalLocked()
	o.mu.Unlock()
	if retire {
		o.cancel()
	}
	if err != nil {
		return personal.NodeLease{}, err
	}
	return lease, nil
}

func (o *ResourceOwner) OnOpen(id string) {
	o.mu.Lock()
	r, exists := o.resources[id]
	if exists && r.active && !o.closing {
		o.mu.Unlock()
		return
	}
	if exists && !o.closing && o.ctx.Err() == nil && time.Now().Before(r.end) && o.lifecycle.tryOpen(id) {
		r.active = true
		o.resources[id] = r
		o.signalLocked()
		o.mu.Unlock()
		return
	}
	// Fence before calling native (CloseSession may synchronously OnClose).
	if exists {
		delete(o.resources, id)
		o.lifecycle.CloseReservation(id)
	}
	o.operations++
	retire := o.retireLocked()
	o.mu.Unlock()
	if retire {
		o.cancel()
	}
	o.control.CloseSession(id)
	o.operationDone()
}

func (o *ResourceOwner) OnClose(id string) {
	o.mu.Lock()
	r, exists := o.resources[id]
	if !exists {
		o.mu.Unlock()
		return
	}
	delete(o.resources, id)
	o.operations++
	retire := o.retireLocked()
	o.signalLocked()
	o.mu.Unlock()
	if retire {
		o.cancel()
	}
	if r.active {
		o.lifecycle.OnClose(id)
	} else {
		o.lifecycle.CloseReservation(id)
	}
	o.operationDone()
}

func (o *ResourceOwner) operationDone() {
	o.mu.Lock()
	o.operations--
	o.signalLocked()
	o.mu.Unlock()
}

// Fence revokes only this owner's carrier context. Detached reservations are
// journaled without OnOpen; late admission completion is separately fenced.
func (o *ResourceOwner) Fence() {
	o.mu.Lock()
	if o.fenced {
		o.mu.Unlock()
		return
	}
	o.fenced = true
	wasClosing := o.closing
	o.closing = true
	resources := o.resources
	o.resources = make(map[string]carrierResource)
	o.operations++
	o.signalLocked()
	o.mu.Unlock()
	if !wasClosing {
		o.cancel()
	}
	for id, r := range resources {
		if r.active {
			o.lifecycle.OnClose(id)
		} else {
			o.lifecycle.CloseReservation(id)
		}
	}
	o.operationDone()
}

// One scheduler covers all bounded unpublished leases. Active lease expiry and
// renewal stay exclusively native/policy-owned; no cleanup timer renews them.
func (o *ResourceOwner) expireReservations() {
	defer close(o.done)
	for {
		o.mu.Lock()
		if o.closing {
			o.mu.Unlock()
			return
		}
		now := time.Now()
		var next time.Time
		for id, r := range o.resources {
			if r.active {
				continue
			}
			if !now.Before(r.end) {
				delete(o.resources, id)
				o.lifecycle.CloseReservation(id)
			} else if next.IsZero() || r.end.Before(next) {
				next = r.end
			}
		}
		retire := o.retireLocked()
		changed := o.changed
		o.mu.Unlock()
		if retire {
			o.cancel()
			return
		}
		var tick <-chan time.Time
		stop := func() {}
		if !next.IsZero() {
			tick, stop = o.lifecycle.newTimer(time.Until(next))
		}
		select {
		case <-o.ctx.Done():
			stop()
			o.Fence()
			return
		case <-changed:
			stop()
		case <-tick:
			stop()
		}
	}
}

// JoinWorkers covers this scheduler, pending Home calls and resource callbacks.
// Call Lifecycle.JoinWorkers separately after native callbacks are quiescent;
// neither method proves provider ACK or the enclosing process's physical exit.
func (o *ResourceOwner) JoinWorkers(ctx context.Context) error {
	for {
		o.mu.Lock()
		ready := o.closing && o.pending == 0 && o.operations == 0
		changed := o.changed
		o.mu.Unlock()
		if ready {
			select {
			case <-o.done:
				return nil
			case <-ctx.Done():
				return ctx.Err()
			}
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}
