#ifndef RGA_TUN_STOP_H
#define RGA_TUN_STOP_H
#include <stdint.h>
/* One prepared/running JNI bridge. Stop requests are not completion witnesses. */
uint64_t rga_tun_stop_prepare(void);
int rga_tun_stop_enter(uint64_t epoch);
int rga_tun_stop_request(uint64_t epoch);
int rga_tun_stop_publish(int writer);
void rga_tun_stop_unpublish(int writer);
int rga_tun_stop_finish(uint64_t epoch);
int rga_tun_stop_retired(uint64_t epoch);
int rga_tun_stop_abort_prepared(uint64_t epoch);
#endif
