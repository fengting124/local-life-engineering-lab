package com.personalprojections.locallife.server.module.shop.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import com.personalprojections.locallife.server.module.shop.dto.CreateShopRequest;
import com.personalprojections.locallife.server.module.shop.dto.ShopVO;
import com.personalprojections.locallife.server.module.shop.dto.UpdateShopRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店 Service，负责门店的创建、查询、修改和状态变更。
 *
 * <h2>权限校验策略</h2>
 * <p>所有写操作（创建、更新、上线、下线）都有两层权限校验：
 * <ol>
 *   <li><b>身份校验</b>：当前用户必须是 APPROVED 状态的商家（调 MerchantService.requireApprovedMerchant()）</li>
 *   <li><b>归属校验</b>：操作的门店必须属于当前商家（shop.merchantId == merchant.id），防止越权操作</li>
 * </ol>
 *
 * <h2>状态机保护</h2>
 * <p>门店状态变更遵循预定义的合法流转路径：
 * <pre>
 *   DRAFT   → ONLINE   （上线）
 *   ONLINE  → OFFLINE  （下线）
 *   OFFLINE → ONLINE   （恢复上线）
 *   ONLINE  → CLOSED   （永久关闭）
 *   OFFLINE → CLOSED   （永久关闭）
 *
 *   非法流转（如 CLOSED → ONLINE）会抛 SHOP_STATUS_ILLEGAL。
 * </pre>
 *
 * <h2>C 端 vs B 端查询的区别</h2>
 * <ul>
 *   <li>C 端（用户）：只能查 ONLINE 状态的门店，按分类、关键词搜索</li>
 *   <li>B 端（商家）：可以查自己所有状态的门店（含 DRAFT/OFFLINE）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ShopMapper shopMapper;

    /**
     * MerchantService 提供「校验当前用户是否是 APPROVED 商家」的能力。
     * Service 之间相互调用是允许的，但要避免循环依赖。
     * 这里 ShopService → MerchantService，单向依赖，没有循环。
     */
    private final MerchantService merchantService;

    // =========================================================
    // 查询（公开，C 端）
    // =========================================================

    /**
     * 查询门店详情（公开接口，无需登录）。
     *
     * <p>只能查 ONLINE 状态的门店，DRAFT/OFFLINE/CLOSED 状态返回 SHOP_NOT_FOUND。
     * 原因：不暴露未上线的门店信息，防止竞品爬取草稿数据。
     *
     * @param shopId 门店 ID
     * @return 门店详情 VO
     */
    public ShopVO getShopDetail(Long shopId) {
        Shop shop = shopMapper.selectById(shopId);
        // 门店不存在，或状态不是 ONLINE，统一返回 SHOP_NOT_FOUND（不区分，防枚举攻击）
        if (shop == null || !"ONLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        return toVO(shop);
    }

    /**
     * 搜索门店列表（公开接口，C 端）。
     *
     * <p>当前阶段：只支持按分类筛选，按 score 降序排列。
     * 后续接入 Elasticsearch 后，此方法会被替换为 ES 查询，
     * 支持全文检索、地理位置距离排序、多字段权重等能力。
     *
     * @param categoryId 分类 ID，为 null 时不按分类筛选（查所有分类）
     * @return ONLINE 状态的门店列表
     */
    public List<ShopVO> searchShops(Integer categoryId) {
        LambdaQueryWrapper<Shop> wrapper = new LambdaQueryWrapper<Shop>()
                // 只查 ONLINE 状态（C 端不展示未上线门店）
                .eq(Shop::getStatus, "ONLINE")
                // categoryId 不为 null 时才追加分类过滤条件
                // LambdaQueryWrapper 的 condition 参数：第一个参数为 true 时才追加此条件
                .eq(categoryId != null, Shop::getCategoryId, categoryId)
                // 按评分降序，评分相同按创建时间降序（新门店排后面）
                .orderByDesc(Shop::getScore)
                .orderByDesc(Shop::getCreatedAt);

        return shopMapper.selectList(wrapper)
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =========================================================
    // 写操作（商家端，需要鉴权）
    // =========================================================

    /**
     * 创建门店（商家操作）。
     *
     * <p>前置校验：
     * <ol>
     *   <li>当前用户必须是 APPROVED 状态的商家</li>
     * </ol>
     * 创建后门店状态为 DRAFT，需要商家手动触发上线（PUT /shops/{id}/status/online）。
     *
     * @param request 创建请求体（已通过 @Valid 校验）
     * @return 创建后的门店 VO（status = DRAFT）
     */
    public ShopVO createShop(CreateShopRequest request) {
        // 1. 校验当前用户是否是 APPROVED 商家，获取 merchantId
        Merchant merchant = merchantService.requireApprovedMerchant();

        // 2. 构建门店实体，初始状态为 DRAFT
        Shop shop = Shop.builder()
                .merchantId(merchant.getId())
                .shopName(request.getShopName())
                .categoryId(request.getCategoryId())
                .coverImage("")
                .description(request.getDescription() != null ? request.getDescription() : "")
                .address(request.getAddress())
                .longitude(request.getLongitude())
                .latitude(request.getLatitude())
                .phone(request.getPhone() != null ? request.getPhone() : "")
                .businessHours(request.getBusinessHours() != null ? request.getBusinessHours() : "")
                .score(BigDecimal.ZERO)   // 新门店评分为 0，后续由任务计算
                .status("DRAFT")
                .build();

        shopMapper.insert(shop);
        log.info("门店创建成功，merchantId: {}, shopId: {}, shopName: {}",
                merchant.getId(), shop.getId(), shop.getShopName());
        return toVO(shop);
    }

    /**
     * 更新门店信息（商家操作，全量更新）。
     *
     * <p>前置校验：
     * <ol>
     *   <li>当前用户是 APPROVED 商家</li>
     *   <li>目标门店属于当前商家（防越权）</li>
     *   <li>门店不是 CLOSED 状态（CLOSED 是终态，不允许修改）</li>
     * </ol>
     *
     * @param shopId  目标门店 ID
     * @param request 更新请求体
     * @return 更新后的门店 VO
     */
    public ShopVO updateShop(Long shopId, UpdateShopRequest request) {
        // 1. 权限校验（同时获取 merchantId 和 shop）
        Shop shop = requireOwnShop(shopId);

        // 2. CLOSED 状态不允许修改（终态）
        if ("CLOSED".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        // 3. 更新字段（全量更新：所有字段都覆盖）
        shop.setShopName(request.getShopName());
        shop.setCategoryId(request.getCategoryId());
        shop.setDescription(request.getDescription() != null ? request.getDescription() : "");
        shop.setAddress(request.getAddress());
        shop.setLongitude(request.getLongitude());
        shop.setLatitude(request.getLatitude());
        shop.setPhone(request.getPhone() != null ? request.getPhone() : "");
        shop.setBusinessHours(request.getBusinessHours() != null ? request.getBusinessHours() : "");
        // score、status、merchantId 不在此处更新

        shopMapper.updateById(shop);
        log.info("门店更新成功，shopId: {}", shopId);
        return toVO(shop);
    }

    /**
     * 门店上线（DRAFT/OFFLINE → ONLINE）。
     *
     * <p>上线后用户端可以看到该门店。
     * 合法的前置状态：DRAFT 或 OFFLINE（其他状态拒绝）。
     *
     * @param shopId 目标门店 ID
     * @return 更新状态后的门店 VO
     */
    public ShopVO onlineShop(Long shopId) {
        Shop shop = requireOwnShop(shopId);

        // 只有 DRAFT 或 OFFLINE 才能上线
        if (!"DRAFT".equals(shop.getStatus()) && !"OFFLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        // 状态机保护：使用带条件的 UPDATE，只有当前状态符合预期才更新
        // 即使并发情况下也不会出现状态错误流转
        shop.setStatus("ONLINE");
        shopMapper.updateById(shop);
        log.info("门店上线，shopId: {}", shopId);
        return toVO(shop);
    }

    /**
     * 门店下线（ONLINE → OFFLINE）。
     *
     * <p>下线后用户端不再展示该门店，但商家仍可管理。
     * 合法的前置状态：仅 ONLINE（其他状态拒绝）。
     *
     * @param shopId 目标门店 ID
     * @return 更新状态后的门店 VO
     */
    public ShopVO offlineShop(Long shopId) {
        Shop shop = requireOwnShop(shopId);

        if (!"ONLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
        }

        shop.setStatus("OFFLINE");
        shopMapper.updateById(shop);
        log.info("门店下线，shopId: {}", shopId);
        return toVO(shop);
    }

    /**
     * 查询当前商家的所有门店（B 端管理后台）。
     *
     * <p>返回当前商家名下所有状态的门店（含 DRAFT/OFFLINE）。
     * 按创建时间倒序排列，方便商家查看最新创建的门店。
     *
     * @return 当前商家的门店列表
     */
    public List<ShopVO> listMyShops() {
        Merchant merchant = merchantService.requireApprovedMerchant();

        return shopMapper.selectList(
                        new LambdaQueryWrapper<Shop>()
                                .eq(Shop::getMerchantId, merchant.getId())
                                .orderByDesc(Shop::getCreatedAt)
                )
                .stream()
                .map(this::toVO)
                .collect(Collectors.toList());
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 校验门店存在且属于当前登录商家，返回门店实体。
     *
     * <p>两个校验合并处理（不区分「不存在」和「无权限」），
     * 防止攻击者通过不同错误码推断门店是否存在（枚举攻击）。
     *
     * @param shopId 门店 ID
     * @return 门店实体
     * @throws BizException SHOP_FORBIDDEN，门店不存在或不属于当前商家
     */
    private Shop requireOwnShop(Long shopId) {
        // 先获取当前登录商家（同时校验商家身份）
        Merchant merchant = merchantService.requireApprovedMerchant();

        Shop shop = shopMapper.selectById(shopId);

        // 门店不存在，或不属于当前商家，统一返回 SHOP_FORBIDDEN
        if (shop == null || !merchant.getId().equals(shop.getMerchantId())) {
            throw new BizException(ErrorCode.SHOP_FORBIDDEN);
        }
        return shop;
    }

    /**
     * Shop 实体转 ShopVO。
     *
     * <p>Long 型 ID 转 String，防止 JS Number 精度截断（雪花 ID 超过 2^53-1）。
     */
    private ShopVO toVO(Shop shop) {
        return ShopVO.builder()
                .shopId(String.valueOf(shop.getId()))
                .merchantId(String.valueOf(shop.getMerchantId()))
                .shopName(shop.getShopName())
                .categoryId(shop.getCategoryId())
                .coverImage(shop.getCoverImage())
                .description(shop.getDescription())
                .address(shop.getAddress())
                .longitude(shop.getLongitude())
                .latitude(shop.getLatitude())
                .phone(shop.getPhone())
                .businessHours(shop.getBusinessHours())
                .score(shop.getScore())
                .status(shop.getStatus())
                .build();
    }
}
