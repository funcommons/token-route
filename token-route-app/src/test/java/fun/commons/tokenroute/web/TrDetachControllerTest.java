package fun.commons.tokenroute.web;

import fun.commons.framework4j.web.ApiException;
import fun.commons.tokenroute.TrRouteEngine;
import fun.commons.tokenroute.config.TrTableDefinition;
import fun.commons.tokenroute.config.TrTableRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TR-CTR-003 亲和解除端点分支（02 §6）：未注册表 10400 / session 超长 10100 / 委托引擎幂等回显。
 */
class TrDetachControllerTest {

    private final TrRouteEngine engine = mock(TrRouteEngine.class);
    private final TrDetachController controller = new TrDetachController(engine, registry());

    private static TrTableRegistry registry() {
        TrTableDefinition d = new TrTableDefinition();
        d.setName("t1");
        d.setRefreshUrl("http://feed.example/t1");
        return TrTableRegistry.load(List.of(d));
    }

    @Test
    void unknownTableIs404() {
        TrDetachRequest r = new TrDetachRequest();
        r.setTableId("ghost");
        r.setSessionId("s1");
        ApiException ex = catchThrowableOfType(() -> controller.detach(r), ApiException.class);
        assertThat(ex.getCode()).isEqualTo(10400);
    }

    @Test
    void oversizedSessionRejected() {
        TrDetachRequest r = new TrDetachRequest();
        r.setTableId("t1");
        r.setSessionId("s".repeat(129));
        assertThat(catchThrowableOfType(() -> controller.detach(r), ApiException.class))
                .isNotNull();
    }

    @Test
    void delegatesToEngineAndEchoesDetached() {
        when(engine.detach("t1", "s1")).thenReturn(true);
        TrDetachRequest r = new TrDetachRequest();
        r.setTableId("t1");
        r.setSessionId("s1");
        assertThat(controller.detach(r).getData()).containsEntry("detached", true);
        verify(engine).detach(eq("t1"), eq("s1"));
    }
}
