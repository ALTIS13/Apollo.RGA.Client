-keep class org.olcbox.app.vpn.service.OlcboxVpnService {
    *;
}

# R33 additive gomobile read via reflection; retain exact method name and JNI binding.
-keepclassmembers class mobile.Runtime {
    public java.lang.String transportHealth();
}
