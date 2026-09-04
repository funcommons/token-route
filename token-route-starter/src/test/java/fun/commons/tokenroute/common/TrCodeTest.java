package fun.commons.tokenroute.common;

import fun.commons.framework4j.api.ApiCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TrCode 枚举测试：通用段与 fwk4j ApiCode 对齐 + 业务诊断码 10630/10633。
 * 07_实施计划 S0：TDD 枚举测试先行。
 */
class TrCodeTest {

    @Test
    void genericSegmentAlignsWithFwk4jApiCode() {
        // 通用段沿用 mc-api-spec / fwk4j ApiCode 数值——对齐测试防止漂移
        assertThat(TrCode.OK.getCode()).isEqualTo(ApiCode.SUCCESS.getCode());
        assertThat(TrCode.PARAM_ERROR.getCode()).isEqualTo(ApiCode.PARAM_ERROR.getCode());
        assertThat(TrCode.PARAM_MISSING.getCode()).isEqualTo(ApiCode.PARAM_MISSING.getCode());
        assertThat(TrCode.UNAUTHORIZED.getCode()).isEqualTo(ApiCode.UNAUTHORIZED.getCode());
        assertThat(TrCode.NOT_FOUND.getCode()).isEqualTo(ApiCode.NOT_FOUND.getCode());
        assertThat(TrCode.PARTIAL_SUCCESS.getCode()).isEqualTo(ApiCode.PARTIAL_SUCCESS.getCode());
    }

    @Test
    void diagnosticCodesArePinned() {
        // 02_接口契约 §4.2：10630 启动期脚本编译失败 / 10633 FEED 配置 schema 校验拒绝；10631/10632 已随管理写面删除
        assertThat(TrCode.SCRIPT_COMPILE_FAILED.getCode()).isEqualTo(10630);
        assertThat(TrCode.FEED_CONFIG_INVALID.getCode()).isEqualTo(10633);
        assertThat(TrCode.SCRIPT_COMPILE_FAILED.getMessage()).contains("脚本");
        assertThat(TrCode.FEED_CONFIG_INVALID.getMessage()).contains("校验");
    }

    @Test
    void codesAreUnique() {
        Set<Integer> seen = new HashSet<>();
        for (TrCode c : TrCode.values()) {
            assertThat(seen.add(c.getCode())).as("重复码 %d (%s)", c.getCode(), c.name()).isTrue();
        }
    }

    @Test
    void ofResolvesByCode() {
        assertThat(TrCode.of(10633)).contains(TrCode.FEED_CONFIG_INVALID);
        assertThat(TrCode.of(10700)).contains(TrCode.PARTIAL_SUCCESS);
        assertThat(TrCode.of(10631)).isEmpty();
        assertThat(TrCode.of(-1)).isEmpty();
    }

    @Test
    void reservedSegmentNotOccupied() {
        // 10634~10639 预留段不得占用；10631/10632 已删除不得复活
        for (TrCode c : TrCode.values()) {
            assertThat(c.getCode()).as("%s 不得占用已删除/预留段", c.name())
                    .isNotIn(10631, 10632, 10634, 10635, 10636, 10637, 10638, 10639);
        }
    }
}
