-- Redis 令牌桶限流脚本（照搬 Pack 10-reliability/redis-token-bucket.lua）。
--
-- ★ 为什么必须用 Lua 脚本，而不是四条分开的 Redis 命令：
--   算令牌要四步：读当前值 → 算这段时间补了多少 → 判断够不够 → 写回新值。
--   四条命令分开发过去，中间会被别的请求插进来（TOCTOU 竞态）。
--   Redis 执行 Lua 是原子的，四步变一步——这是 L15/L16 那个思想的第三种形态：
--     改已有的行  → 条件 UPDATE，affectedRows 裁决
--     建全新的行  → 抢占 INSERT，唯一键冲突裁决
--     Redis 侧    → Lua 脚本，脚本执行期间不被打断
--   三种实现、一个道理：让"检查"和"动作"在一个不可分割的单位里完成。
--
-- KEYS[1]: rate-limit key（由 Java 侧按场景拼，如 "agentlog:rl:ticket-status:42"）
-- ARGV[1]: capacity（令牌桶容量，突发上限）
-- ARGV[2]: refill_per_second（每秒补充速率）
-- ARGV[3]: now_ms（当前毫秒时间戳，由 Java 传入，不用 redis.call("TIME") 保证可测试）
-- ARGV[4]: requested_tokens（本次请求消耗几个，通常是 1）
--
-- 返回值：{allowed, remaining_tokens}
--   allowed = 1 → 放行；0 → 拒绝
--   remaining_tokens = 拒绝时桶里剩余令牌数（调试用）
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

local state = redis.call("HMGET", key, "tokens", "ts")
local tokens = tonumber(state[1]) or capacity
local ts = tonumber(state[2]) or now
local delta = math.max(0, now - ts) / 1000
tokens = math.min(capacity, tokens + delta * refill)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call("HMSET", key, "tokens", tokens, "ts", now)
-- TTL = 桶填满所需时间的 2 倍，避免空 key 永久占内存
redis.call("PEXPIRE", key, math.ceil((capacity / refill) * 2000))
return {allowed, math.floor(tokens)}
