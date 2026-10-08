import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;

/**
 * PadGuard 许可证发码工具（运营方内部使用，**不得随发布包分发**）。
 *
 * 用法（Java 17 单文件源码运行）：
 *   java LicenseTool.java keygen                      生成密钥对 -> 输出 public.key / private.key
 *   java LicenseTool.java issue YEARLY 5 ORDER001     发放年卡，5 台设备
 *   java LicenseTool.java issue MONTHLY 3 ORDER002 30 自指定天数
 *
 * 为什么用 Ed25519 而不是 HMAC：服务端内置的是**公钥**，家长拿到安装包也拿不到私钥，
 * 无法自己伪造许可证。若用对称密钥，密钥必然随 jar 分发，等于把发码能力交给了用户。
 */
public class LicenseTool {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "keygen" -> keygen();
            case "issue" -> issue(args);
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("""
            用法:
              java LicenseTool.java keygen
              java LicenseTool.java issue <MONTHLY|QUARTERLY|YEARLY> <devices> <orderSeq> [days]
            """);
    }

    /** 生成密钥对。private.key 必须妥善保管且不得分发；public.key 供服务端内置。 */
    private static void keygen() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair kp = kpg.generateKeyPair();
        String pub = B64.encodeToString(kp.getPublic().getEncoded());
        String pri = B64.encodeToString(kp.getPrivate().getEncoded());
        Files.writeString(Path.of("license-public.key"), pub);
        Files.writeString(Path.of("license-private.key"), pri);
        System.out.println("public.key  -> " + pub);
        System.out.println("private.key -> 已写入 license-private.key（切勿分发）");
    }

    private static void issue(String[] args) throws Exception {
        if (args.length < 4) { usage(); return; }
        String plan = args[1].toUpperCase();
        int devices = Integer.parseInt(args[2]);
        String seq = args[3];

        long days = switch (plan) {
            case "MONTHLY" -> 30;
            case "QUARTERLY" -> 90;
            case "YEARLY" -> 365;
            default -> throw new IllegalArgumentException("未知套餐: " + plan);
        };
        if (args.length >= 5) days = Long.parseLong(args[4]);

        long from = System.currentTimeMillis();
        long to = from + days * 86_400_000L;

        String payload = String.format(
            "{\"v\":1,\"plan\":\"%s\",\"dev\":%d,\"from\":%d,\"to\":%d,\"seq\":\"%s\"}",
            plan, devices, from, to, seq);

        String priB64 = Files.readString(Path.of("license-private.key")).trim();
        PrivateKey pri = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(B64D.decode(priB64)));

        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(pri);
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        String sigB64 = B64.encodeToString(sig.sign());

        String license = "PG1." + B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sigB64;
        System.out.println("套餐  : " + plan);
        System.out.println("设备数: " + devices);
        System.out.println("有效期: " + from + " ~ " + to + " (" + days + " 天)");
        System.out.println("订单号: " + seq);
        System.out.println();
        System.out.println("许可证密钥:");
        System.out.println(license);
    }
}
