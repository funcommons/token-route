package fun.commons.tokenroute.client;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * report 批量器（01 §7.2 / 02 §6 补发纪律）：100 条批量切分、10700 只补发 rejected、flush/close 语义。
 */
class TrReportBatcherTest {

    @Test
    void bufferFullTriggersBatchSend() {
        List<List<String>> sent = new ArrayList<>();
        TrReportBatcher<String> batcher = new TrReportBatcher<>(3, 60_000, batch -> {
            sent.add(new ArrayList<>(batch));
            return List.of();
        });
        batcher.add("r1");
        batcher.add("r2");
        assertThat(batcher.buffered()).isEqualTo(2);
        batcher.add("r3");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).containsExactly("r1", "r2", "r3");
        assertThat(batcher.buffered()).isZero();
        batcher.close();
    }

    @Test
    void partialSuccessResubmitsOnlyRejected() {
        List<List<String>> sent = new ArrayList<>();
        TrReportBatcher<String> batcher = new TrReportBatcher<>(3, 60_000, batch -> {
            sent.add(new ArrayList<>(batch));
            // 第二轮（补发）全部受理
            if (sent.size() >= 2) {
                return List.of();
            }
            return List.of("r2"); // 10700：仅 r2 被拒
        });
        batcher.add("r1");
        batcher.add("r2");
        batcher.add("r3");
        batcher.close();

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0)).containsExactly("r1", "r2", "r3");
        // 只补发 rejected，不整批重发（accepted 部分 win/fail 双计数会污染状态机）
        assertThat(sent.get(1)).containsExactly("r2");
    }

    @Test
    void rejectedTwiceIsDroppedWithHook() {
        List<String> dropped = new ArrayList<>();
        TrReportBatcher<String> batcher = new TrReportBatcher<>(1, 60_000, batch -> List.of(batch.get(0))) {
            @Override
            protected void onDropped(List<String> d) {
                dropped.addAll(d);
            }
        };
        batcher.add("bad");
        assertThat(dropped).containsExactly("bad");
        batcher.close();
    }

    @Test
    void closeFlushesRemainder() {
        List<List<String>> sent = new ArrayList<>();
        TrReportBatcher<String> batcher = new TrReportBatcher<>(100, 60_000, batch -> {
            sent.add(new ArrayList<>(batch));
            return List.of();
        });
        batcher.add("a");
        batcher.add("b");
        batcher.close();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).containsExactly("a", "b");
    }

    @Test
    void addAfterCloseThrows() {
        TrReportBatcher<String> batcher = new TrReportBatcher<>(10, 60_000, b -> List.of());
        batcher.close();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> batcher.add("x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
