#include <jni.h>
#include <stdint.h>
#include <unistd.h>

#include "hev-main.h"
#include "hev-mapped-dns.h"
#include "rga-tun-flow-proof.h"
#include "rga-tun-stop.h"

JNIEXPORT jlong JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_prepareTun2socksNative(
    JNIEnv *env, jobject thiz)
{
    return (jlong)rga_tun_stop_prepare();
}

JNIEXPORT jlong JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_prepareTunDnsScopeNative(
    JNIEnv *env, jobject thiz)
{
    return (jlong)hev_mapped_dns_scope_begin();
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_closeTunDnsScopeNative(
    JNIEnv *env, jobject thiz, jlong scope)
{
    return scope > 0 && hev_mapped_dns_scope_close((uint64_t)scope) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_releasePreparedTun2socksNative(
    JNIEnv *env, jobject thiz, jlong epoch)
{
    return epoch > 0 && rga_tun_stop_abort_prepared((uint64_t)epoch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_isTun2socksNativeRetired(
    JNIEnv *env, jobject thiz, jlong epoch)
{
    return epoch > 0 && rga_tun_stop_retired((uint64_t)epoch) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_startTun2socksNative(
    JNIEnv *env, jobject thiz, jstring config_path, jint tun_fd, jlong epoch, jlong dns_scope)
{
    if (epoch <= 0 || !rga_tun_stop_enter((uint64_t)epoch)) {
        close(tun_fd);
        if (epoch > 0) rga_tun_stop_abort_prepared((uint64_t)epoch);
        return -1;
    }
    /* The worker reservation is captured first. An old/closing TUN owner
       cannot enter HEV, and a rejected duplicate cannot touch either owner. */
    if (dns_scope <= 0 || !hev_mapped_dns_scope_enter((uint64_t)dns_scope)) {
        close(tun_fd);
        return rga_tun_stop_finish((uint64_t)epoch) ? -1 : -2;
    }
    const char *path = (*env)->GetStringUTFChars(env, config_path, 0);
    if (path == 0) {
        close(tun_fd);
        if (!hev_mapped_dns_scope_leave((uint64_t)dns_scope)) return -2;
        return rga_tun_stop_finish((uint64_t)epoch) ? -1 : -2;
    }

    int result = hev_socks5_tunnel_main_from_file(path, tun_fd);
    rga_tun_flow_clear(0);
    (*env)->ReleaseStringUTFChars(env, config_path, path);
    close(tun_fd);
    /* Real HEV fini must already detach its borrowed map. An unknown leave
       retains the native reservation, not a false retirement receipt. */
    if (!hev_mapped_dns_scope_leave((uint64_t)dns_scope)) return -2;
    if (!rga_tun_stop_finish((uint64_t)epoch)) return -2;
    return result;
}

JNIEXPORT jboolean JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_stopTun2socksNative(
    JNIEnv *env, jobject thiz, jlong epoch)
{
    int requested = epoch > 0 && rga_tun_stop_request((uint64_t)epoch);
    /* The exact worker clears its proof before releasing its reservation.
       A delayed/stale stop must not clear a successor's armed flow. */
    return requested ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_armPersonalTunFlowNative(
    JNIEnv *env, jobject thiz, jlong epoch, jlong remote, jint local_port, jint remote_port)
{
    if (epoch <= 0 || remote <= 0 || remote > UINT32_MAX || local_port <= 0 ||
        local_port > UINT16_MAX || remote_port <= 0 || remote_port > UINT16_MAX) {
        rga_tun_flow_clear(0);
        return;
    }
    rga_tun_flow_arm((uint64_t)epoch, (uint32_t)remote, (uint16_t)local_port, (uint16_t)remote_port);
}

JNIEXPORT void JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_clearPersonalTunFlowNative(
    JNIEnv *env, jobject thiz, jlong epoch)
{
    if (epoch > 0) rga_tun_flow_clear((uint64_t)epoch);
}

JNIEXPORT jlongArray JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_readPersonalTunFlowNative(
    JNIEnv *env, jobject thiz, jlong epoch)
{
    RgaTunFlowProof p = rga_tun_flow_read((uint64_t)epoch);
    jlong values[6] = {p.epoch, p.tx_packets, p.rx_packets, p.tx_payload, p.rx_payload, p.local_ipv4};
    jlongArray result = (*env)->NewLongArray(env, 6);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 6, values);
    return result;
}

JNIEXPORT jlongArray JNICALL
Java_org_olcbox_app_vpn_service_OlcboxVpnService_getTun2socksStatsNative(
    JNIEnv *env, jobject thiz)
{
    size_t tx_packets = 0;
    size_t tx_bytes = 0;
    size_t rx_packets = 0;
    size_t rx_bytes = 0;
    jlong values[4];

    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result == 0) {
        return 0;
    }
    (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    return result;
}
