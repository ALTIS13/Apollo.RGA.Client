#include "rga-tun-stop.h"
#include <errno.h>
#include <limits.h>
#include <pthread.h>
#include <sys/socket.h>

enum { STOP_IDLE, STOP_PREPARED, STOP_RUNNING };
static pthread_mutex_t stop_lock = PTHREAD_MUTEX_INITIALIZER;
static uint64_t next_epoch;
static uint64_t current_epoch;
static int phase;
static int stop_requested;
static int writer_fd = -1;

/* No waiting for initialization, no blocking socket write and no raw fd after
 * the lock is dropped. event_task_fini unpublishes BEFORE closing the writer. */
static int signal_writer(void)
{
    const char value = 1;
    int result;
    int retries = 0;
    if (writer_fd < 0) return 1;
    do {
        result = (int)send(writer_fd, &value, 1, MSG_DONTWAIT | MSG_NOSIGNAL);
    } while (result < 0 && errno == EINTR && ++retries < 4);
    return result == 1 || (result < 0 && (errno == EAGAIN || errno == EWOULDBLOCK));
}

uint64_t rga_tun_stop_prepare(void)
{
    uint64_t result = 0;
    pthread_mutex_lock(&stop_lock);
    if (phase == STOP_IDLE && next_epoch < INT64_MAX) {
        result = current_epoch = ++next_epoch;
        phase = STOP_PREPARED;
        stop_requested = 0;
        writer_fd = -1;
    }
    pthread_mutex_unlock(&stop_lock);
    return result;
}

int rga_tun_stop_enter(uint64_t epoch)
{
    int result;
    pthread_mutex_lock(&stop_lock);
    result = epoch != 0 && epoch == current_epoch && phase == STOP_PREPARED && !stop_requested;
    if (result) phase = STOP_RUNNING;
    pthread_mutex_unlock(&stop_lock);
    return result;
}

int rga_tun_stop_request(uint64_t epoch)
{
    int result = 0;
    pthread_mutex_lock(&stop_lock);
    if (epoch != 0 && epoch == current_epoch && phase != STOP_IDLE) {
        stop_requested = 1;
        result = signal_writer();
    }
    pthread_mutex_unlock(&stop_lock);
    return result;
}

int rga_tun_stop_publish(int writer)
{
    int result = 1;
    pthread_mutex_lock(&stop_lock);
    if (phase != STOP_IDLE) {
        result = phase == STOP_RUNNING && writer >= 0 && writer_fd < 0;
        if (result) {
            writer_fd = writer;
            if (stop_requested) result = signal_writer();
        }
    }
    pthread_mutex_unlock(&stop_lock);
    return result;
}

void rga_tun_stop_unpublish(int writer)
{
    pthread_mutex_lock(&stop_lock);
    if (writer_fd == writer) writer_fd = -1;
    pthread_mutex_unlock(&stop_lock);
}

int rga_tun_stop_finish(uint64_t epoch)
{
    int result;
    pthread_mutex_lock(&stop_lock);
    result = epoch != 0 && epoch == current_epoch && phase == STOP_RUNNING && writer_fd < 0;
    if (result) phase = STOP_IDLE;
    pthread_mutex_unlock(&stop_lock);
    return result;
}

int rga_tun_stop_abort_prepared(uint64_t epoch)
{
    int result;
    pthread_mutex_lock(&stop_lock);
    result = epoch != 0 && epoch == current_epoch && phase == STOP_PREPARED;
    if (result) phase = STOP_IDLE;
    pthread_mutex_unlock(&stop_lock);
    return result;
}

int rga_tun_stop_retired(uint64_t epoch)
{
    int result;
    pthread_mutex_lock(&stop_lock);
    result = epoch != 0 && epoch == current_epoch && phase == STOP_IDLE && writer_fd < 0;
    pthread_mutex_unlock(&stop_lock);
    return result;
}
