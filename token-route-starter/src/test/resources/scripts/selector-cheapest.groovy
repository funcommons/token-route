// selector：成本最低者胜出（先剔除缺价条目，防 num() 零值陷阱，05 §6 示例 1）
def priced = entries.findAll { it.data_json.unit_price != null }
priced.isEmpty() ? null : priced.min { num(it.data_json.unit_price) }.entry_id
