package node

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/ALTIS13/Apollo.RGA.Client/access-bridge/internal/personal"
)

const transientRetryInterval = 25 * time.Second

type sessionHooks interface {
	Renew(context.Context, string) error
	Close(context.Context, string) error
}

type sessionControl interface {
	CloseSession(string) bool
}

type activeSession struct {
	cancel context.CancelFunc
	done   chan struct{}
}

type Lifecycle struct {
	mu             sync.Mutex
	active         map[string]*activeSession
	newTimer       func(time.Duration) (<-chan time.Time, func())
	parent         context.Context
	hooks          sessionHooks
	control        sessionControl
	interval       time.Duration
	retryInterval  time.Duration
	attemptTimeout time.Duration
	stopping       bool
	workers        int
	workersChanged chan struct{}
}

// NewLifecycle drives only already admitted managed sessions. Native lease and
// meter enforcement remain authoritative; this wrapper never grants either.
func NewLifecycle(parent context.Context, hooks sessionHooks, control sessionControl, interval time.Duration) *Lifecycle {
	return &Lifecycle{
		active: make(map[string]*activeSession), parent: parent,
		hooks: hooks, control: control, interval: interval,
		retryInterval: transientRetryInterval, attemptTimeout: 10 * time.Second,
		newTimer: func(d time.Duration) (<-chan time.Time, func()) {
			timer := time.NewTimer(d)
			return timer.C, func() { timer.Stop() }
		},
	}
}

// NewRelayLifecycle uses the existing renewal loop with timings bounded by the
// 30s control-plane lease. A failed refresh never changes the native cutoff.
func NewRelayLifecycle(parent context.Context, hooks sessionHooks, control sessionControl) *Lifecycle {
	l := NewLifecycle(parent, hooks, control, 15*time.Second)
	l.retryInterval, l.attemptTimeout = 5*time.Second, 5*time.Second
	return l
}

// OnOpen starts fresh Bridge revalidation only after native reports a
// successful session. A callback racing shutdown is cut rather than revived.
func (l *Lifecycle) OnOpen(id string) {
	if l.tryOpen(id) {
		return
	}
	l.mu.Lock()
	l.workerStartedLocked()
	l.mu.Unlock()
	defer l.workerFinished()
	l.control.CloseSession(id)
	l.closeJournal(id)
}

// tryOpen only publishes local renewal ownership. It never calls native/user
// callbacks, so a resource owner can serialize it with its exact reservation.
func (l *Lifecycle) tryOpen(id string) bool {
	l.mu.Lock()
	if l.stopping || l.parent.Err() != nil || l.active[id] != nil {
		l.mu.Unlock()
		return false
	}
	ctx, cancel := context.WithCancel(l.parent)
	entry := &activeSession{cancel: cancel, done: make(chan struct{})}
	l.active[id] = entry
	l.workerStartedLocked()
	l.mu.Unlock()
	go l.renewLoop(ctx, id, entry)
	return true
}

func (l *Lifecycle) renewLoop(ctx context.Context, id string, entry *activeSession) {
	defer l.workerFinished()
	defer close(entry.done)
	delay := l.interval
	for {
		tick, stop := l.newTimer(delay)
		select {
		case <-ctx.Done():
			stop()
			return
		case <-tick:
			stop()
			if ctx.Err() != nil {
				return
			}
			attempt, cancel := context.WithTimeout(ctx, l.attemptTimeout)
			err := l.hooks.Renew(attempt, id)
			cancel()
			if ctx.Err() != nil {
				return
			}
			if err == nil {
				delay = l.interval
				continue
			}
			if errors.Is(err, personal.ErrNodeUnavailable) {
				// A transient Bridge/Panel outage cannot extend the native
				// deadline. Retry sooner while its existing lease remains valid.
				delay = l.retryInterval
				continue
			}
			l.control.CloseSession(id)
			l.OnClose(id) // fallback if native close was already in flight
			return
		}
	}
}

// OnClose cancels future renewal and records the exact terminal session once.
func (l *Lifecycle) OnClose(id string) {
	l.mu.Lock()
	entry := l.active[id]
	if entry != nil {
		l.workerStartedLocked()
		delete(l.active, id)
		entry.cancel()
	}
	l.mu.Unlock()
	if entry != nil {
		defer l.workerFinished()
		l.closeJournal(id)
	}
}

func (l *Lifecycle) closeJournal(id string) {
	ctx, done := l.startJournal(id)
	select {
	case <-done:
	case <-ctx.Done():
	}
}

// CloseReservation records an issued but unpublished lease without inventing
// OnOpen. Completion is tracked independently of the journal's success/ACK.
func (l *Lifecycle) CloseReservation(id string) { l.startJournal(id) }

func (l *Lifecycle) startJournal(id string) (context.Context, <-chan struct{}) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	done := make(chan struct{})
	l.mu.Lock()
	l.workerStartedLocked()
	l.mu.Unlock()
	go func() {
		defer l.workerFinished()
		defer cancel()
		defer close(done)
		_ = l.hooks.Close(ctx, id)
	}()
	return ctx, done
}

// Shutdown stops every session known to this wrapper before canceling native
// Run; a missing native callback still cannot leave the QA row active here.
func (l *Lifecycle) Shutdown() {
	l.mu.Lock()
	if l.stopping {
		l.mu.Unlock()
		return
	}
	l.stopping = true
	l.workerStartedLocked()
	defer l.workerFinished()
	ids := make([]string, 0, len(l.active))
	for id, entry := range l.active {
		entry.cancel()
		ids = append(ids, id)
	}
	l.mu.Unlock()
	var closing sync.WaitGroup
	for _, id := range ids {
		closing.Add(1)
		go func(id string) {
			defer closing.Done()
			l.control.CloseSession(id)
			l.OnClose(id)
		}(id)
	}
	closing.Wait()
}

func (l *Lifecycle) workerStartedLocked() {
	l.workers++
	if l.workersChanged == nil {
		l.workersChanged = make(chan struct{})
	}
}

func (l *Lifecycle) workerFinished() {
	l.mu.Lock()
	l.workers--
	if l.workersChanged != nil {
		close(l.workersChanged)
	}
	l.workersChanged = make(chan struct{})
	l.mu.Unlock()
}

// JoinWorkers is meaningful after Shutdown and after the producer of native
// callbacks is quiescent. A canceled context reports unjoined work; it does not
// kill it or turn a journal failure into an ACK. Provider/process joins are not
// covered by this local renewal/journal barrier.
func (l *Lifecycle) JoinWorkers(ctx context.Context) error {
	for {
		l.mu.Lock()
		if l.stopping && l.workers == 0 {
			l.mu.Unlock()
			return nil
		}
		if l.workersChanged == nil {
			l.workersChanged = make(chan struct{})
		}
		changed := l.workersChanged
		l.mu.Unlock()
		select {
		case <-changed:
		case <-ctx.Done():
			return ctx.Err()
		}
	}
}
