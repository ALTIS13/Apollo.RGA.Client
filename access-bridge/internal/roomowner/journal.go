package roomowner

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
)

type Phase string

const (
	Creating        Phase = "creating"
	Created         Phase = "created"
	CreationUnknown Phase = "creation_unknown"
	Rejected        Phase = "rejected"
	Closing         Phase = "closing"
	Closed          Phase = "closed"
	CleanupUnknown  Phase = "cleanup_unknown"
)

type Record struct {
	Binding  Binding
	Revision uint32
	Phase    Phase
	Room     CreatedRoom
}

func (Record) String() string { return "room journal record [private]" }

// FileJournal is RU-local private, append-only custody. Every revision is
// exclusively created and synced before return; uncertain writes remain on disk
// and block adoption/retry. Nothing is overwritten, repaired or deleted here.
// Actual runtime qualification requires Linux private storage and directory fsync;
// Windows host tests cover the transaction, not power-loss durability.
type FileJournal struct{ root string }

func NewFileJournal(root string) (*FileJournal, error) {
	if !filepath.IsAbs(root) || filepath.Clean(root) != root {
		return nil, ErrConfig
	}
	// Deployment prepares the explicit private root. Do not create an arbitrary
	// ancestor chain whose directory entries have not been made durable.
	if !privateDirectory(root) || syncDirectory(root) != nil || syncDirectory(filepath.Dir(root)) != nil {
		return nil, ErrJournal
	}
	return &FileJournal{root: root}, nil
}

func privateDirectory(path string) bool {
	info, err := os.Lstat(path)
	return err == nil && info.IsDir() && info.Mode()&os.ModeSymlink == 0 &&
		(runtime.GOOS == "windows" || info.Mode().Perm()&0077 == 0)
}

func (j *FileJournal) directory(b Binding) string {
	h := sha256.Sum256([]byte(b.Worker + "\x00" + b.Epoch + "\x00" + b.Room))
	return filepath.Join(j.root, hex.EncodeToString(h[:]))
}

func syncDirectory(path string) error {
	// Windows is a source-test host only; production RU is Linux. Directory
	// FlushFileBuffers is not portable on Windows. Do not claim a Linux crash
	// proof from this branch or activate this store on a Windows RTC worker.
	if runtime.GOOS == "windows" {
		return nil
	}
	f, err := os.Open(path)
	if err != nil {
		return ErrJournal
	}
	defer f.Close()
	if f.Sync() != nil {
		return ErrJournal
	}
	return nil
}

func (j *FileJournal) Claim(b Binding) (bool, error) {
	b = b.canonical()
	if !b.valid() || !privateDirectory(j.root) {
		return false, ErrJournal
	}
	dir := j.directory(b)
	if err := os.Mkdir(dir, 0700); err != nil {
		if os.IsExist(err) && privateDirectory(dir) {
			// Even an empty directory may be another owner's interrupted claim.
			// A missing first record never licenses recreating an attempt.
			return false, nil
		}
		return false, ErrJournal
	}
	if !privateDirectory(dir) || syncDirectory(j.root) != nil {
		return false, ErrJournal
	}
	err := j.append(dir, Record{Binding: b, Phase: Creating})
	if os.IsExist(err) {
		return false, nil
	}
	if err != nil {
		return false, ErrJournal
	}
	return true, nil
}

func validStep(before Record, phase Phase, room CreatedRoom) bool {
	switch before.Phase {
	case Creating:
		return (phase == Created && room.valid()) || ((phase == CreationUnknown || phase == Rejected) && room == (CreatedRoom{}))
	case Created:
		return phase == Closing && room == before.Room
	case Closing:
		return (phase == Closed || phase == CleanupUnknown) && room == before.Room
	default:
		return false
	}
}

func (j *FileJournal) Advance(before Record, phase Phase, room CreatedRoom) (Record, error) {
	current, err := j.Load(before.Binding)
	if err != nil || current != before || !validStep(before, phase, room) {
		return Record{}, ErrJournal
	}
	next := Record{Binding: before.Binding, Revision: before.Revision + 1, Phase: phase, Room: room}
	if j.append(j.directory(before.Binding), next) != nil {
		return Record{}, ErrJournal
	}
	return next, nil
}

func (j *FileJournal) append(dir string, r Record) error {
	data, err := json.Marshal(r)
	if err != nil || len(data) > 16<<10 {
		return ErrJournal
	}
	path := filepath.Join(dir, fmt.Sprintf("%06d.json", r.Revision))
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	_, writeErr := f.Write(data)
	syncErr := f.Sync()
	closeErr := f.Close()
	if writeErr != nil || syncErr != nil || closeErr != nil || syncDirectory(dir) != nil {
		return ErrJournal
	}
	return nil
}

func (j *FileJournal) Load(b Binding) (Record, error) {
	b = b.canonical()
	if !b.valid() || !privateDirectory(j.root) || !privateDirectory(j.directory(b)) {
		return Record{}, ErrJournal
	}
	directory, err := os.Open(j.directory(b))
	if err != nil {
		return Record{}, ErrJournal
	}
	names, readErr := directory.Readdirnames(5)
	closeErr := directory.Close()
	if (readErr != nil && readErr != io.EOF) || closeErr != nil || len(names) < 1 || len(names) > 4 {
		return Record{}, ErrJournal
	}
	sort.Strings(names)
	for n, name := range names {
		if name != fmt.Sprintf("%06d.json", n) {
			return Record{}, ErrJournal
		}
	}
	var previous Record
	for n := uint32(0); n < uint32(len(names)); n++ {
		path := filepath.Join(j.directory(b), fmt.Sprintf("%06d.json", n))
		info, err := os.Lstat(path)
		if err != nil || !info.Mode().IsRegular() || info.Size() < 1 || info.Size() > 16<<10 ||
			(runtime.GOOS != "windows" && info.Mode().Perm()&0077 != 0) {
			return Record{}, ErrJournal
		}
		f, err := os.Open(path)
		if err != nil {
			return Record{}, ErrJournal
		}
		data, readErr := io.ReadAll(io.LimitReader(f, (16<<10)+1))
		closeErr := f.Close()
		if readErr != nil || closeErr != nil || len(data) > 16<<10 {
			return Record{}, ErrJournal
		}
		var current Record
		decoder := json.NewDecoder(bytes.NewReader(data))
		decoder.DisallowUnknownFields()
		if decoder.Decode(&current) != nil || decoder.Decode(new(any)) != io.EOF || current.Binding != b || current.Revision != n {
			return Record{}, ErrJournal
		}
		if n == 0 {
			if current.Phase != Creating || current.Room != (CreatedRoom{}) {
				return Record{}, ErrJournal
			}
		} else if !validStep(previous, current.Phase, current.Room) {
			return Record{}, ErrJournal
		}
		previous = current
	}
	return previous, nil
}
