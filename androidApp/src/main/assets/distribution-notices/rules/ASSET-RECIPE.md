# Reproducing release assets

The corresponding-source release includes `release-assets.py`, `srs_codec.go`
and the full font/data licences. The APK includes this readable recipe and
attribution. None of these tools executes on application startup.

## Country rules

1. Obtain the free DB-IP IP-to-Country Lite CSV for the intended monthly release
   from https://db-ip.com/db/download/ip-to-country-lite . Do not substitute a paid
   database or MaxMind source. For October2026, the published CSV SHA1 is
   c8ad1dfe98cb29bcacd82a5c3ec361c880e5bb59; release input SHA256 is
   097426b8ddae89157d444a59ac1847e873f7943c32d52becc4371c8b0273af80
   (gzip container); decompressed CSV SHA256 is
   53864a68fbfef02c27c717d08d431a6d453e9e00e77d9e8da841d382267fca88.
   The advertised SHA1 matches the decompressed CSV, not the gzip container.
2. Run `python release-assets.py country --input CSV.gz --sha1 EXPECTED --output DIR`.
   The script validates each three-field UTF-8 CSV row, address family and ordered
   endpoints; selects RU/IR/CN, rejects non-public selected prefixes, decomposes
   exact inclusive ranges into CIDRs, collapses only equal contiguous coverage,
   and writes JSON (IPv4+IPv6) and text (IPv4). October2026 has710834 source rows.
3. Extract the exact sing-box source archive for commit
   25a600db24f7680ad9806ce5427bd0ab8afe1114 (archive SHA256
   9331d29d82a2fdcc432945288a635edc5fdaf39007465165cc133f7f41882087)
   supplied by the corresponding-core source package. In that module directory,
   compile `go build -mod=readonly -o srs-codec PATH/srs_codec.go`. The release used
   Go1.26.8 and existing locked module dependencies. No server is started.
4. Run `srs-codec geoip-ru.json geoip-ru.srs` (also IR/CN). It encodes SRS1, decodes
   it with the same shipped codec and requires exact IP-set equality before
   writing the new exclusive output. Do not replace a failed roundtrip with a file.
5. Update the six country-resource SHA256 pins in RuleSets/XrayGeodata and retain
   the acquisition/attribution notice. Domain and blocked-service rules are not
   changed by this recipe. Run the resource hash/format and real routing gates
   before distributing a signed APK through the own-server update channel.

DB-IP Lite country classifications are approximate. Equal range coverage in a
conversion is not equal classification to another dataset, nor a performance
guarantee. Signed monthly APK refreshes replace the offline embedded set; users
are never required to fetch GitHub data before connecting.

## Modified Sans font names

The immediate input distributor is Ghostlane commit
3b0c0f3b561293b2b831bc9c153c3ecded6fe234; use its three static Sans files with
input SHA256s listed in `release-assets.py`. fontTools4.64.0 is used only at build
time. Run `python release-assets.py fonts --input FONTDIR --output NEWFONTDIR`.

Keep font filenames for resource compatibility. Internal family/full/PostScript/
unique/typographic identifiers use Apollo RGA Sans/ApolloRgaSans and the declared
weight. Preserve copyright/designer/licence names and text, head.modified and
all glyph/metric/other tables. Only name records and the required font checksum
adjustment change. The original IBM authorship and full OFL1.1 remain attached;
the modified primary name does not use the Reserved Font Name Plex. Mono and
Space Grotesk are not transformed. No endorsement is claimed.
