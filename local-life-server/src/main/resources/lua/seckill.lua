--[[
  秒杀抢券 Lua 脚本（原子性防超卖 + 防重复领取）

  调用方：SeckillService.doSeckill()
  执行环境：Redis 单线程，此脚本执行期间不会被其他命令打断（原子性保证）

  KEYS 参数（按顺序传入）：
    KEYS[1] = seckill:stock:{sessionId}:{couponTemplateId}   -- 库存计数 Key（String）
    KEYS[2] = seckill:user:{sessionId}:{couponTemplateId}    -- 已抢用户集合 Key（Set）

  ARGV 参数：
    ARGV[1] = userId（字符串形式，如 "10001"）

  返回值（Long）：
    0 → 抢购成功（预扣库存成功，用户已加入集合）
    1 → 库存不足（当前库存 ≤ 0）
    2 → 重复领取（该用户已在集合中）

  脚本逻辑（伪代码）：
    1. SISMEMBER users_key userId → 已在集合 → return 2
    2. GET stock_key → 库存 ≤ 0 → return 1
    3. DECR stock_key
    4. SADD users_key userId
    5. return 0

  为什么三步操作（判重 + 判库存 + 扣减）必须原子执行？
    假设不用 Lua，用普通 Redis 命令：
      线程 A: SISMEMBER → false（没抢过）
      线程 B: SISMEMBER → false（没抢过）
      线程 A: GET stock = 1（有货）
      线程 B: GET stock = 1（有货）
      线程 A: DECR → 0（扣成功）
      线程 B: DECR → -1（超卖！）
    Lua 脚本在 Redis 里是原子的，线程 B 要等线程 A 的脚本完整执行完才能运行，
    所以线程 B SISMEMBER 时线程 A 的 SADD 已经执行了，或者 DECR 已经让库存为 0。
    从根本上消除了竞争条件，这是 Redis 秒杀去并发的核心原理。
]]

-- 取出参数
local stock_key  = KEYS[1]
local user_key   = KEYS[2]
local user_id    = ARGV[1]

-- 步骤 1：判重（O(1)，Set 成员检查）
-- SISMEMBER 返回 1 表示已在集合中（已领取过），返回 0 表示未领取
if redis.call('SISMEMBER', user_key, user_id) == 1 then
    -- 该用户已经抢过了，直接拒绝
    return 2
end

-- 步骤 2：判库存
-- GET 返回字符串，tonumber() 转成数字
local stock = tonumber(redis.call('GET', stock_key))
if stock == nil or stock <= 0 then
    -- 库存为 nil（Key 不存在，说明还没初始化）或 ≤ 0（已售罄）
    return 1
end

-- 步骤 3：原子性扣减库存 + 记录用户（这两步中间不会被打断）
redis.call('DECR', stock_key)          -- 库存 -1
redis.call('SADD', user_key, user_id)  -- 把当前用户加入「已领取」集合

-- 返回 0 表示成功
return 0
