package org.olcbox.app.net

import multiplatform_app.sharedui.generated.resources.Res

/**
 * The sing-box rule-sets the app ships, and what the configs call them.
 *
 * Domain rule-sets come from SagerNet's `rule-set` branches and v2fly data.
 * Country IP rule-sets use DB-IP IP-to-Country Lite October 2026 (CC BY 4.0),
 * converted without widening ranges using the pinned sing-box SRS codec.
 * The Russian domain lists are `geosite:category-ru` and `geosite:tld-ru`. The TLD
 * list is a file of its own because SagerNet's
 * build of category-ru leaves the bare TLDs out despite the `include:tld-ru`
 * in the v2fly source — without it `sberbank.ru` is matched and `ozon.ru` is
 * not, which is not a list anyone would recognise as "Russia".
 *
 * Bundled rather than downloaded. The networks this mode exists for are the
 * ones where github.com answers slowly or not at all, and a list that arrives
 * after the first connection is a first connection with no bypass in it.
 * Release input hashes and country-source provenance accompany the distributed
 * source/notice packet. Monthly country refreshes ship in signed APK updates;
 * [RuleSetsTest] refuses a bundle whose bytes differ from the pins here.
 */
object RuleSets {
    class File(val name: String, val tag: String, val sha256: String)

    val GEOSITE_RU = File(
        name = "geosite-category-ru.srs",
        tag = "geosite-ru",
        sha256 = "c36e157adf86edf7b722b51f3acb93bbb2a7f8083932dae29b4b5ef2c1ced870"
    )
    val GEOSITE_TLD_RU = File(
        name = "geosite-tld-ru.srs",
        tag = "geosite-tld-ru",
        sha256 = "ba979268102429754bdbbf306890f91ac4ee0cf1d2f30eb1fff5eba65e0f9e66"
    )
    val GEOIP_RU = File(
        name = "geoip-ru.srs",
        tag = "geoip-ru",
        sha256 = "7eff5d0d665eb3745c9a613d758000aa24d5b865d8bf836413e2125e7a0ec7f6"
    )

    val GEOSITE_CATEGORY_IR = File(name = "geosite-category-ir.srs", tag = "geosite-category-ir", sha256 = "5e4ef5289e0b4f73018854730b2ab049860ec1e87e1a601fdbe976bcb6c669b6")
    val GEOSITE_CN = File(name = "geosite-cn.srs", tag = "geosite-cn", sha256 = "a32d727f1a71b2f9c67627ea4ae489d8d6cfb010f8ec2d6800538493fe5a77ae")
    val GEOSITE_TLD_CN = File(name = "geosite-tld-cn.srs", tag = "geosite-tld-cn", sha256 = "246d4792b8d1959854d485420e4f85ea906f85562860c08a34bfd20b1a33c630")
    val GEOIP_IR = File(name = "geoip-ir.srs", tag = "geoip-ir", sha256 = "a88589dbde1742d121b88ba64d1d2754aae80d094a5b8f6e2d50f0d44ee6c13b")
    val GEOIP_CN = File(name = "geoip-cn.srs", tag = "geoip-cn", sha256 = "95195e3fefeb6c1c372e241d6aed26004473aaa34e5867040b3a3433c53de9eb")

    /**
     * Sites blocked in Russia, for "only blocked sites through the tunnel": Re:filter
     * (github.com/1andrevich/Re-filter-lists, MIT), its sing-box rule-sets bundled
     * unmodified — 81k domain suffixes and 25k address prefixes, pinned by release in
     * `scripts/rule-sets.lock`.
     */
    val BLOCKED_DOMAINS = File(name = "refilter-domains.srs", tag = "refilter-domains", sha256 = "2756235a05407dced55bd362f80daeafd19a87ed9720f98cbf69343023a5b534")
    val BLOCKED_IPS = File(name = "refilter-ips.srs", tag = "refilter-ips", sha256 = "381fcfaaf4d2b95b0b3c5ece1cb3525eb2f34f41013930ad25409a29deca3daa")
    val blocked: List<File> = listOf(BLOCKED_DOMAINS, BLOCKED_IPS)
    /** Everything the route rules match on. */
    val all: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU, GEOIP_RU)

    /** The name lists, which is what a DNS rule can match. An IP list has no names. */
    val domains: List<File> = listOf(GEOSITE_RU, GEOSITE_TLD_RU)

    /**
     * Where iOS keeps them, relative to libbox's working directory.
     *
     * Relative because only the extension knows the App Group's absolute path,
     * and libbox resolves a relative `rule_set.path` against the working path
     * it was set up with (`filemanager.BasePath`). The app writes the files
     * there through the Swift bridge; the config never needs the full path.
     */
    const val IOS_RELATIVE_DIR = "rules"


    /** Bundled data: available offline before the first connection. */
    val bundled: List<File> = all + listOf(GEOSITE_CATEGORY_IR, GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_IR, GEOIP_CN) + blocked

    fun regional(region: String): List<File> = when (region) {
        "ru" -> all
        "ir" -> listOf(GEOSITE_CATEGORY_IR, GEOIP_IR)
        "cn" -> listOf(GEOSITE_CN, GEOSITE_TLD_CN, GEOIP_CN)
        else -> error("Unsupported routing region: $region")
    }

    fun regionalDomains(region: String): List<File> = regional(region).filter { it.name.startsWith("geosite-") }

    /** The files [routing]'s policy matches on: none of its own under Tunnel. */
    fun selected(routing: Routing.RuleBased): List<File> = when (val policy = routing.policy) {
        Routing.Policy.Tunnel -> emptyList()
        is Routing.Policy.Bypass -> regional(policy.region)
        Routing.Policy.BlockedOnly -> blocked
    }

    suspend fun bytes(file: File): ByteArray = Res.readBytes("files/rules/${file.name}")
}
