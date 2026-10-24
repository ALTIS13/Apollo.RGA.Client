import org.olcbox.app.PersonalRuIpv4Parser;

// Standalone JDK regression fixture: no body/address values are printed.
class PersonalRuIpv4ParserTest {
    private static int cases;
    public static void main(String[] args) {
        accepted("<html><script>" + fields("1.1.1.1", "1.1.1.1", "0") + "</script></html>");
        accepted("\n" + fields("8.8.8.8", "8.8.8.8", "0") + "\n");
        accepted("<script>{\"clientRegion\":{\"ip\":\"metadata\"}," +
                "\"translations\":{\"ipv4\":{\"isPlural\":false,\"form\":\"IPv4\"}}," +
                "\"echo\":{\"ipv4\":\"https://ipv4-internet.yandex.net/api/v0/ip\"}," +
                "\"backupEcho\":{\"ipv4\":\"https://ipv4-internet.yandex.net/api/v0/ip\"}," +
                "\"observation\":" + fields("1.1.1.1", "1.1.1.1", "0") + "}</script>");
        rejected(null);
        rejected("");
        rejected(fields("1.1.1.1", "8.8.8.8", "0"));
        rejected(fields("1.1.1.1", "1.1.1.1", "1"));
        rejected(fields("1.1.1.1", "1.1.1.1", "00"));
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + fields("1.1.1.1", "1.1.1.1", "0"));
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + ",\"ip\":null");
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + ",\"ipv4\":{}");
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + ",\"ipv4\":{\"value\":null,\"status\":0}");
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + ",\"ip\":{\"v4\":null,\"v6\":null}");
        rejected(fields("1.1.1.1", "1.1.1.1", "0") + ",\"ipv4\":{\"unknown\":true}");
        rejected("<html>captcha</html>" + fields("1.1.1.1", "1.1.1.1", "0"));
        rejected("IP: 1.1.1.1");
        rejected("{\"ip\":{\"v4\":\"1.1.1.1\",\"v6\":null}}");
        rejected(fields("01.1.1.1", "01.1.1.1", "0"));
        rejected(fields("256.1.1.1", "256.1.1.1", "0"));
        rejected(fields("1.1.1.1:443", "1.1.1.1:443", "0"));
        rejected(fields("1.1.1.1", "1.1.1.1", "0").replace("\"v6\":null", "\"v6\":false"));
        for (String nonPublic : new String[] {
                "0.1.1.1", "10.0.0.1", "127.0.0.1", "100.64.0.1", "100.127.255.254",
                "169.254.1.1", "172.16.0.1", "172.31.0.1", "192.168.1.1", "192.0.0.1",
                "192.0.2.1", "198.18.0.1", "198.19.0.1", "198.51.100.1", "203.0.113.1",
                "224.0.0.1", "255.255.255.255" }) {
            rejected(fields(nonPublic, nonPublic, "0"));
        }
        rejected("x".repeat(1_048_577) + fields("1.1.1.1", "1.1.1.1", "0"));
        System.out.println("PASS cases=" + cases);
    }
    private static String fields(String a, String b, String status) {
        return "{\"ip\":{\"v4\":\"" + a + "\",\"v6\":null}," +
                "\"ipv4\":{\"value\":\"" + b + "\",\"status\":" + status + "}}";
    }
    private static void accepted(String html) {
        cases++;
        if (PersonalRuIpv4Parser.parse(html) == null) throw new AssertionError("accepted_case_" + cases);
    }
    private static void rejected(String html) {
        cases++;
        if (PersonalRuIpv4Parser.parse(html) != null) throw new AssertionError("rejected_case_" + cases);
    }
}
