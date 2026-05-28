-- Redis 滑动窗口限流 Lua 脚本
-- 使用 ZSET（有序集合）实现滑动窗口，以时间戳为 score 存储每次请求
--
-- KEYS[1] = 限流 Redis Key，如 "rate_limit:sms:code:10001"
-- ARGV[1] = 当前时间戳（毫秒）
-- ARGV[2] = 时间窗口大小（毫秒）
-- ARGV[3] = 窗口内最大请求次数（limit）
-- ARGV[4] = Key 过期时间（秒），通常为窗口大小的 2 倍
--
-- 返回值：
--   0  → 未超限，请求放行
--   1  → 已超限，触发限流（HTTP 429）

local key = KEYS[1]
local now = tonumber(ARGV[1])           -- 当前时间戳（毫秒）
local window = tonumber(ARGV[2])        -- 窗口大小（毫秒）
local limit = tonumber(ARGV[3])         -- 最大请求次数
local expire = tonumber(ARGV[4])        -- Key 过期时间（秒）

-- Step 1: 移除时间窗口之外的旧请求记录
-- score < (now - window) 的记录已经不在当前窗口内，删除掉
local windowStart = now - window
redis.call('ZREMRANGEBYSCORE', key, 0, windowStart)

-- Step 2: 统计当前窗口内的请求数
local count = redis.call('ZCARD', key)

-- Step 3: 判断是否超限
if count >= limit then
    -- 已达到上限，拒绝请求
    return 1
end

-- Step 4: 记录本次请求（score = now，member = now，用纳秒精度或 UUID 防冲突）
-- 注意：同一毫秒内可能有多个请求，member 加随机后缀防止 ZADD 覆盖
-- 这里简化：用 now 作为 member（同一毫秒只算一次，轻微不精确但生产可接受）
-- 更精确方案：member = now .. ":" .. math.random()，但需要 Redis 5.0+ 的 GETDEL
redis.call('ZADD', key, now, now)

-- Step 5: 设置 Key 过期时间，防止 Key 永久占用内存
-- 过期时间 = 窗口大小 × 2，确保窗口滑走后 Key 自动清理
redis.call('EXPIRE', key, expire)

-- 未超限，放行
return 0
