package org.olcbox.app;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** QA-only strict Internetometer HTML reader; this HTML is not a documented API. */
public final class PersonalRuIpv4Parser {
    private PersonalRuIpv4Parser() {}
    public static String parse(String html) {
        if (html == null || html.length() > MAX_BYTES ||
                html.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES ||
                html.toLowerCase(Locale.ROOT).contains("captcha")) return null;
        // Same-named metadata keys occur in clientRegion, translations and echo URLs.
        // Count records by their structured shape, never select the first address-looking text.
        if (INVALID_IP_SCALAR.matcher(html).find() || INVALID_IPV4_SCALAR.matcher(html).find() ||
                count(IP_OBJECT, html) != 1 || countIpv4Records(html) != 1) return null;
        Matcher ip = IP.matcher(html), ipv4 = IPV4.matcher(html);
        if (!ip.find() || !ipv4.find()) return null;
        String address = ip.group(1);
        return address.equals(ipv4.group(1)) && isPublicIpv4(address) ? address : null;
    }

    private static int count(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        int count = 0;
        while (matcher.find()) if (++count > 1) break;
        return count;
    }

    private static int countIpv4Records(String html) {
        Matcher objects = IPV4_OBJECT.matcher(html);
        int count = 0;
        while (objects.find()) {
            if (VALUE_START.matcher(html).region(objects.end(), html.length()).lookingAt()) {
                if (++count > 1) return count;
            } else if (!TRANSLATION_START.matcher(html).region(objects.end(), html.length()).lookingAt()) {
                // An empty/unknown/malformed object is not silently discarded as metadata.
                return -1;
            }
        }
        return count;
    }

    private static boolean isPublicIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
            octets[i] = Integer.parseInt(parts[i]);
            if (octets[i] > 255) return false;
        }
        int a = octets[0], b = octets[1], c = octets[2];
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b >= 64 && b <= 127) || (a == 169 && b == 254) ||
                (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
                (a == 192 && b == 0 && (c == 0 || c == 2)) ||
                (a == 198 && (b == 18 || b == 19)) || (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113));
    }

    private static final int MAX_BYTES = 1_048_576;
    private static final Pattern IP_OBJECT = Pattern.compile("\"ip\"\\s*:\\s*\\{");
    private static final Pattern IPV4_OBJECT = Pattern.compile("\"ipv4\"\\s*:\\s*\\{");
    private static final Pattern INVALID_IP_SCALAR = Pattern.compile("\"ip\"\\s*:\\s*(?:null|true|false|[-0-9])");
    private static final Pattern INVALID_IPV4_SCALAR = Pattern.compile("\"ipv4\"\\s*:\\s*(?:null|true|false|[-0-9])");
    private static final Pattern VALUE_START = Pattern.compile("\\s*\"value\"\\s*:");
    private static final Pattern TRANSLATION_START = Pattern.compile("\\s*\"isPlural\"\\s*:");
    private static final Pattern IP = Pattern.compile(
            "\"ip\"\\s*:\\s*\\{\\s*\"v4\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"v6\"\\s*:\\s*null\\s*\\}");
    private static final Pattern IPV4 = Pattern.compile(
            "\"ipv4\"\\s*:\\s*\\{\\s*\"value\"\\s*:\\s*\"([^\"]*)\"\\s*,\\s*\"status\"\\s*:\\s*0\\s*\\}");
}
