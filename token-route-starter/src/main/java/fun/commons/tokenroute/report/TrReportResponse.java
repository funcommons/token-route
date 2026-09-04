package fun.commons.tokenroute.report;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;

/**
 * TR-CTR-002 响应：{accepted, rejected:[{index, code, message}]}；
 * 存在拒收 → 整体 10700（02 §6；补发纪律：只补发 rejected 条目）。
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TrReportResponse {

    private int accepted;
    private List<Rejected> rejected = new ArrayList<>();

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Rejected {
        private int index;
        private int code;
        private String message;

        public Rejected() {
        }

        public Rejected(int index, int code, String message) {
            this.index = index;
            this.code = code;
            this.message = message;
        }

        public int getIndex() {
            return index;
        }

        public void setIndex(int index) {
            this.index = index;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    public int getAccepted() {
        return accepted;
    }

    public void setAccepted(int accepted) {
        this.accepted = accepted;
    }

    public List<Rejected> getRejected() {
        return rejected;
    }

    public void setRejected(List<Rejected> rejected) {
        this.rejected = rejected;
    }
}
