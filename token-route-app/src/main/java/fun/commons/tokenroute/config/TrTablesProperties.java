package fun.commons.tokenroute.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 路由表 config 种子（tr.tables，01_设计方案 §4.1）。
 */
@ConfigurationProperties(prefix = "tr")
public class TrTablesProperties {

    private List<TrTableDefinition> tables = new ArrayList<>();

    public List<TrTableDefinition> getTables() {
        return tables;
    }

    public void setTables(List<TrTableDefinition> tables) {
        this.tables = tables;
    }
}
