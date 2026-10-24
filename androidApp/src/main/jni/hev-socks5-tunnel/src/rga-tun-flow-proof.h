#ifndef RGA_TUN_FLOW_PROOF_H
#define RGA_TUN_FLOW_PROOF_H
#include <stddef.h>
#include <stdint.h>

/* One transient, bounded IPv4/TCP QA flow; never retains packet data. */
typedef struct {
    uint64_t epoch, tx_packets, rx_packets, tx_payload, rx_payload;
    uint32_t local_ipv4;
} RgaTunFlowProof;

void rga_tun_flow_arm(uint64_t epoch, uint32_t remote_ipv4,
                      uint16_t local_port, uint16_t remote_port);
void rga_tun_flow_clear(uint64_t epoch);
int rga_tun_flow_is_armed(void);
void rga_tun_flow_observe(const unsigned char *header, size_t header_bytes,
                          size_t packet_bytes, int sending);
RgaTunFlowProof rga_tun_flow_read(uint64_t epoch);
#endif
