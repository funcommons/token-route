// 合法 filter：租户白名单（05 §6 示例 2）
len(entry.data_json.tenants) == 0 || in(params.tenant, entry.data_json.tenants)
