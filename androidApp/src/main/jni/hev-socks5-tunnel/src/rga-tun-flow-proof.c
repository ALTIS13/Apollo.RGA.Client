#include "rga-tun-flow-proof.h"
#include <pthread.h>
#include <stdatomic.h>

static pthread_mutex_t guard = PTHREAD_MUTEX_INITIALIZER;
static atomic_bool armed;
static RgaTunFlowProof proof;
static uint32_t destination;
static uint16_t source_port, destination_port;
static int saw_syn;

static uint16_t read16(const unsigned char *p) { return (uint16_t)((p[0]<<8)|p[1]); }
static uint32_t read32(const unsigned char *p) {
    return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3];
}
static uint64_t bounded_add(uint64_t n, uint64_t amount) {
    const uint64_t cap=1024*1024;
    return n>=cap || amount>cap-n ? cap : n+amount;
}
void rga_tun_flow_arm(uint64_t epoch, uint32_t remote, uint16_t local, uint16_t port) {
    pthread_mutex_lock(&guard);
    atomic_store(&armed,0);
    proof=(RgaTunFlowProof){0}; saw_syn=0;
    destination=remote; source_port=local; destination_port=port;
    if (epoch && remote && local && port) {
        proof.epoch=epoch;
        atomic_store(&armed,1);
    }
    pthread_mutex_unlock(&guard);
}
void rga_tun_flow_clear(uint64_t epoch) {
    pthread_mutex_lock(&guard);
    if (!epoch || epoch==proof.epoch) {
        atomic_store(&armed,0); proof=(RgaTunFlowProof){0}; saw_syn=0;
    }
    pthread_mutex_unlock(&guard);
}
int rga_tun_flow_is_armed(void) { return atomic_load(&armed); }
void rga_tun_flow_observe(const unsigned char *h, size_t n, size_t wire, int sending) {
    if (!atomic_load(&armed) || !h || n<40 || (h[0]>>4)!=4 || h[9]!=6) return;
    unsigned ihl=(h[0]&15)*4;
    if (ihl<20 || ihl+20>n || (read16(h+6)&0x3fff)) return;
    unsigned tcp=(h[ihl+12]>>4)*4;
    unsigned total=read16(h+2);
    if (tcp<20 || ihl+tcp>n || total<ihl+tcp || total>wire) return;
    uint32_t src=read32(h+12), dst=read32(h+16);
    uint16_t sp=read16(h+ihl), dp=read16(h+ihl+2);
    uint64_t payload=total-ihl-tcp;
    pthread_mutex_lock(&guard);
    if (!atomic_load(&armed)) goto done;
    if (sending) {
        if (dst!=destination || sp!=source_port || dp!=destination_port) goto done;
        if (!saw_syn) {
            if ((h[ihl+13]&0x12)!=0x02) goto done;
            saw_syn=1; proof.local_ipv4=src;
        }
        if (src!=proof.local_ipv4) goto done;
        proof.tx_packets=bounded_add(proof.tx_packets,1);
        proof.tx_payload=bounded_add(proof.tx_payload,payload);
    } else {
        if (!saw_syn || src!=destination || dst!=proof.local_ipv4 ||
            sp!=destination_port || dp!=source_port) goto done;
        proof.rx_packets=bounded_add(proof.rx_packets,1);
        proof.rx_payload=bounded_add(proof.rx_payload,payload);
    }
done:
    pthread_mutex_unlock(&guard);
}
RgaTunFlowProof rga_tun_flow_read(uint64_t epoch) {
    pthread_mutex_lock(&guard);
    RgaTunFlowProof result=epoch && epoch==proof.epoch ? proof : (RgaTunFlowProof){0};
    pthread_mutex_unlock(&guard);
    return result;
}
