package fun.commons.tokenroute.resolve;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 确定性 entry_id（02_接口契约 §5）：`e-{hash(table_id, name)}`，零映射键——
 * FEED 按 name 直接寻址 upsert，Redis 丢失重建后 ID 稳定。
 * hash = SHA-256("tableId:name") 前 16 hex 字符。
 */
public final class TrEntryId {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TrEntryId() {
    }

    public static String of(String tableId, String name) {
        if (tableId == null || tableId.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("table_id / name 不能为空");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((tableId + ":" + name).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("e-");
            for (int i = 0; i < 8; i++) {
                int b = digest[i] & 0xff;
                sb.append(HEX[b >>> 4]).append(HEX[b & 0x0f]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
