#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "rga-tun-flow-proof.h"

static unsigned char packet[128];
static const uint32_t remote = 0xc6336408; /* documentation address */
static void be16(unsigned char *p, unsigned v) { p[0]=v>>8; p[1]=v; }
static void be32(unsigned char *p, uint32_t v) { p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v; }
static void make_packet(int tx, unsigned flags, unsigned payload) {
    memset(packet,0,sizeof(packet)); packet[0]=0x45; packet[9]=6;
    be16(packet+2,40+payload);
    be32(packet+12,tx?0x0a0a0002:remote); be32(packet+16,tx?remote:0x0a0a0002);
    be16(packet+20,tx?41001:443); be16(packet+22,tx?443:41001);
    packet[32]=0x50; packet[33]=flags;
}
static void see(int tx,unsigned flags,unsigned payload) {
    make_packet(tx,flags,payload); rga_tun_flow_observe(packet,40,40+payload,tx);
}
int main(void) {
    rga_tun_flow_arm(11,remote,41001,443);
    see(0,0x12,0); see(1,0x18,8); /* no fresh SYN yet */
    assert(rga_tun_flow_read(11).tx_packets==0);
    make_packet(1,2,0); be16(packet+20,41002);
    rga_tun_flow_observe(packet,40,40,1);
    assert(rga_tun_flow_read(11).tx_packets==0);
    see(1,2,0); see(0,0x12,0); see(1,0x18,23); see(0,0x18,61);
    RgaTunFlowProof proof=rga_tun_flow_read(11);
    assert(proof.epoch==11 && proof.local_ipv4==0x0a0a0002);
    assert(proof.tx_packets==2 && proof.rx_packets==2);
    assert(proof.tx_payload==23 && proof.rx_payload==61);
    make_packet(0,0x18,8); be16(packet+22,41002);
    rga_tun_flow_observe(packet,40,48,0);
    make_packet(0,0x18,8); be32(packet+16,0x0a0a0003);
    rga_tun_flow_observe(packet,40,48,0);
    make_packet(0,0x18,8); be16(packet+6,0x2000); /* fragmented */
    rga_tun_flow_observe(packet,40,48,0);
    make_packet(0,0x18,8); packet[0]=0x65; /* unsupported IPv6 */
    rga_tun_flow_observe(packet,40,48,0);
    make_packet(0,0x18,8); packet[32]=0xf0; /* incomplete TCP header */
    rga_tun_flow_observe(packet,40,48,0);
    make_packet(0,0x18,8); rga_tun_flow_observe(packet,39,48,0);
    make_packet(0,0x18,8); rga_tun_flow_observe(packet,40,47,0);
    assert(rga_tun_flow_read(11).rx_payload==61);
    assert(rga_tun_flow_read(12).epoch==0);
    rga_tun_flow_arm(12,remote,41001,443); rga_tun_flow_clear(11);
    see(1,2,0); see(0,0x18,3);
    assert(rga_tun_flow_read(12).rx_payload==3);
    assert(rga_tun_flow_read(11).epoch==0);
    rga_tun_flow_clear(12);
    assert(rga_tun_flow_read(12).epoch==0);
    rga_tun_flow_arm(0,remote,41001,443); see(1,2,0);
    assert(rga_tun_flow_read(0).tx_packets==0);
    puts("personal TUN exact-flow regression tests passed");
    return 0;
}
