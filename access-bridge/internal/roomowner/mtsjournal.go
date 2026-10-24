package roomowner

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"time"
)

type MTSPhase string

const (
	MTSEventCreating      MTSPhase = "event_creating"
	MTSEventCreated       MTSPhase = "event_created"
	MTSSessionCreating    MTSPhase = "session_creating"
	MTSCreated            MTSPhase = "created"
	MTSCreationUnknown    MTSPhase = "creation_unknown"
	MTSStopPending        MTSPhase = "stop_pending"
	MTSStopped            MTSPhase = "stopped"
	MTSDeletePending      MTSPhase = "delete_pending"
	MTSEventDeletePending MTSPhase = "event_delete_pending"
	MTSClosed             MTSPhase = "closed"
	MTSCleanupUnknown     MTSPhase = "cleanup_unknown"
)

type MTSResource struct {
	EventID, SessionID int64
	Locator            string
}

func (MTSResource) String() string   { return "MTS resource [private]" }
func (MTSResource) GoString() string { return "MTS resource [private]" }

type MTSRecord struct {
	Binding  Binding
	Revision uint32
	Phase    MTSPhase
	Resource MTSResource
}

func (MTSRecord) String() string   { return "MTS custody record [private]" }
func (MTSRecord) GoString() string { return "MTS custody record [private]" }

type MTSJournal interface {
	Lock(context.Context, Binding) (func(), error)
	Retire(Binding) error
	Retiring(Binding) (bool, error)
	Claim(Binding) (bool, error)
	Load(Binding) (MTSRecord, error)
	Advance(MTSRecord, MTSPhase, MTSResource) (MTSRecord, error)
}

// Separate bounded transaction: VK's one-call journal must not pretend a
// two-request MTS create is atomic. Shared primitives retain exclusive claims,
// private Linux modes, file fsync then directory fsync and no repair/adoption.
type MTSFileJournal struct{ disk *FileJournal }

func NewMTSFileJournal(root string) (*MTSFileJournal, error) {
	disk, err := NewFileJournal(root)
	if err != nil {
		return nil, err
	}
	return &MTSFileJournal{disk: disk}, nil
}

func (j *MTSFileJournal) sidecar(b Binding, suffix string) string {
	return filepath.Join(j.disk.root, ".mts-"+filepath.Base(j.disk.directory(b))+suffix)
}

// Kernel lock covers external API + returned-ID commit across instances AND
// processes. Closing the descriptor (including process exit) releases it. The
// lock file is never removed: unlinking could split live owners across inodes.
func (j *MTSFileJournal) Lock(ctx context.Context, b Binding) (func(), error) {
	b = b.canonical()
	if ctx == nil || ctx.Err() != nil || !mtsBindingValid(b) || !privateDirectory(j.disk.root) {
		return nil, ErrJournal
	}
	path := j.sidecar(b, ".lock")
	if info, e := os.Lstat(path); e == nil && !info.Mode().IsRegular() {
		return nil, ErrJournal
	} else if e != nil && !os.IsNotExist(e) {
		return nil, ErrJournal
	}
	f, e := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0600)
	if e != nil {
		return nil, ErrJournal
	}
	ok := false
	defer func() {
		if !ok {
			_ = f.Close()
		}
	}()
	info, e := f.Stat()
	if e != nil || !info.Mode().IsRegular() || info.Size() != 0 || (runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		return nil, ErrJournal
	}
	for {
		if ctx.Err() != nil {
			return nil, ErrJournal
		}
		locked, e := mtsTryLock(f)
		if e != nil {
			return nil, ErrJournal
		}
		if locked {
			ok = true
			return func() { _ = f.Close() }, nil
		}
		timer := time.NewTimer(10 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, ErrJournal
		case <-timer.C:
		}
	}
}

// Retirement is independent of the transaction lock so a waiting Close can
// suppress the next create/publication without discarding an in-flight result.
func (j *MTSFileJournal) Retire(b Binding) error {
	b = b.canonical()
	if !mtsBindingValid(b) || !privateDirectory(j.disk.root) {
		return ErrJournal
	}
	data, e := json.Marshal(b)
	if e != nil || len(data) > 16<<10 {
		return ErrJournal
	}
	f, e := os.OpenFile(j.sidecar(b, ".retire"), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if os.IsExist(e) {
		retired, re := j.Retiring(b)
		if re != nil || !retired {
			return ErrJournal
		}
		return nil
	}
	if e != nil {
		return ErrJournal
	}
	n, we := f.Write(data)
	se := f.Sync()
	ce := f.Close()
	if we != nil || n != len(data) || se != nil || ce != nil || syncDirectory(j.disk.root) != nil {
		return ErrJournal
	}
	return nil
}

func (j *MTSFileJournal) Retiring(b Binding) (bool, error) {
	b = b.canonical()
	if !mtsBindingValid(b) || !privateDirectory(j.disk.root) {
		return false, ErrJournal
	}
	path := j.sidecar(b, ".retire")
	info, e := os.Lstat(path)
	if os.IsNotExist(e) {
		return false, nil
	}
	if e != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 16<<10 || (runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
		return false, ErrJournal
	}
	f, e := os.Open(path)
	if e != nil {
		return false, ErrJournal
	}
	data, re := io.ReadAll(io.LimitReader(f, (16<<10)+1))
	ce := f.Close()
	if re != nil || ce != nil || len(data) > 16<<10 {
		return false, ErrJournal
	}
	var original Binding
	d := json.NewDecoder(bytes.NewReader(data))
	d.DisallowUnknownFields()
	if d.Decode(&original) != nil || d.Decode(new(any)) != io.EOF || original != b {
		return false, ErrJournal
	}
	return true, nil
}

func (j *MTSFileJournal) Claim(b Binding) (bool, error) {
	b = b.canonical()
	if !mtsBindingValid(b) || !privateDirectory(j.disk.root) {
		return false, ErrJournal
	}
	dir := j.disk.directory(b)
	if err := os.Mkdir(dir, 0700); err != nil {
		if os.IsExist(err) && privateDirectory(dir) {
			return false, nil
		}
		return false, ErrJournal
	}
	if !privateDirectory(dir) || syncDirectory(j.disk.root) != nil {
		return false, ErrJournal
	}
	if j.append(MTSRecord{Binding: b, Phase: MTSEventCreating}) != nil {
		return false, ErrJournal
	}
	return true, nil
}

func mtsStep(before MTSRecord, phase MTSPhase, r MTSResource) bool {
	// Unknown results retain only positively owned IDs, never an unsafe locator.
	partial := r.EventID >= 0 && r.SessionID >= 0 && (r.EventID > 0 || r.SessionID == 0) && r.Locator == ""
	same := r == before.Resource
	switch before.Phase {
	case MTSEventCreating:
		return (phase == MTSEventCreated && r.EventID > 0 && r.SessionID == 0 && r.Locator == "") ||
			(phase == MTSCreationUnknown && partial && r.SessionID == 0) || (phase == MTSCleanupUnknown && same)
	case MTSEventCreated:
		return same && (phase == MTSSessionCreating || phase == MTSEventDeletePending)
	case MTSSessionCreating:
		return r.EventID == before.Resource.EventID && ((phase == MTSCreated && mtsResourceValid(r)) ||
			(phase == MTSCreationUnknown && partial) || (phase == MTSEventDeletePending && same))
	case MTSCreated:
		return same && phase == MTSStopPending
	case MTSCreationUnknown:
		return same && ((phase == MTSStopPending && r.SessionID > 0) ||
			(phase == MTSEventDeletePending && r.EventID > 0 && r.SessionID == 0) ||
			(phase == MTSCleanupUnknown && r.EventID == 0))
	case MTSStopPending:
		return same && (phase == MTSStopped || phase == MTSCleanupUnknown)
	case MTSStopped:
		return same && phase == MTSDeletePending
	case MTSDeletePending, MTSEventDeletePending:
		return same && (phase == MTSClosed || phase == MTSCleanupUnknown)
	}
	return false
}

func (j *MTSFileJournal) Advance(before MTSRecord, phase MTSPhase, resource MTSResource) (MTSRecord, error) {
	current, err := j.Load(before.Binding)
	if err != nil || current != before || !mtsStep(before, phase, resource) {
		return MTSRecord{}, ErrJournal
	}
	next := MTSRecord{Binding: before.Binding, Revision: before.Revision + 1, Phase: phase, Resource: resource}
	if j.append(next) != nil {
		return MTSRecord{}, ErrJournal
	}
	return next, nil
}

func (j *MTSFileJournal) append(r MTSRecord) error {
	data, err := json.Marshal(r)
	if err != nil || len(data) > 16<<10 {
		return ErrJournal
	}
	dir := j.disk.directory(r.Binding)
	f, err := os.OpenFile(filepath.Join(dir, fmt.Sprintf("%06d.json", r.Revision)), os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return ErrJournal
	}
	n, writeErr := f.Write(data)
	syncErr := f.Sync()
	closeErr := f.Close()
	if writeErr != nil || n != len(data) || syncErr != nil || closeErr != nil || syncDirectory(dir) != nil {
		return ErrJournal
	}
	return nil
}

func (j *MTSFileJournal) Load(b Binding) (MTSRecord, error) {
	b = b.canonical()
	dir := j.disk.directory(b)
	if !mtsBindingValid(b) || !privateDirectory(j.disk.root) || !privateDirectory(dir) {
		return MTSRecord{}, ErrJournal
	}
	d, err := os.Open(dir)
	if err != nil {
		return MTSRecord{}, ErrJournal
	}
	names, readErr := d.Readdirnames(10)
	closeErr := d.Close()
	// Successful lifecycle uses8 records; the extra slot permits terminal
	// quarantine, not an unbounded retry log. Missing or extra names are fatal.
	if (readErr != nil && readErr != io.EOF) || closeErr != nil || len(names) < 1 || len(names) > 9 {
		return MTSRecord{}, ErrJournal
	}
	sort.Strings(names)
	var previous MTSRecord
	for n, name := range names {
		if name != fmt.Sprintf("%06d.json", n) {
			return MTSRecord{}, ErrJournal
		}
		path := filepath.Join(dir, name)
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 16<<10 ||
			(runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
			return MTSRecord{}, ErrJournal
		}
		f, err := os.Open(path)
		if err != nil {
			return MTSRecord{}, ErrJournal
		}
		data, re := io.ReadAll(io.LimitReader(f, (16<<10)+1))
		ce := f.Close()
		if re != nil || ce != nil || len(data) > 16<<10 {
			return MTSRecord{}, ErrJournal
		}
		var current MTSRecord
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&current) != nil || decoder.Decode(new(any)) != io.EOF || current.Binding != b || current.Revision != uint32(n) {
			return MTSRecord{}, ErrJournal
		}
		if n == 0 {
			if current.Phase != MTSEventCreating || current.Resource != (MTSResource{}) {
				return MTSRecord{}, ErrJournal
			}
		} else if !mtsStep(previous, current.Phase, current.Resource) {
			return MTSRecord{}, ErrJournal
		}
		previous = current
	}
	return previous, nil
}
