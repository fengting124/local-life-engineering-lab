package com.personalprojections.locallife.server.module.seckill;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.SeckillSessionMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.seckill.dto.CouponTemplateVO;
import com.personalprojections.locallife.server.module.seckill.service.CouponService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CouponService#listActiveCoupons()} 单元测试。
 *
 * <p>覆盖三条短路分支 + 完整拼装路径：
 * <ul>
 *   <li>无 ACTIVE 场次 → 空列表（不再查模板）</li>
 *   <li>有场次但模板全下线 → 空列表</li>
 *   <li>完整路径：场次 + 模板 + 门店名冗余拼进 VO</li>
 *   <li>门店查不到 → shopName 兜底为空串（getOrDefault），不抛异常</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CouponService 单元测试")
class CouponServiceTest {

    @Mock CouponTemplateMapper couponTemplateMapper;
    @Mock SeckillSessionMapper seckillSessionMapper;
    @Mock ShopMapper shopMapper;

    @InjectMocks CouponService couponService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, SeckillSession.class);
        TableInfoHelper.initTableInfo(assistant, CouponTemplate.class);
    }

    private SeckillSession session(long id, long templateId) {
        return SeckillSession.builder()
                .id(id).couponTemplateId(templateId).seckillStock(100)
                .beginTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(1))
                .sessionStatus("ACTIVE").build();
    }

    private CouponTemplate template(long id, long shopId) {
        return CouponTemplate.builder()
                .id(id).shopId(shopId).couponName("满100减20").discountType("CASH")
                .discountValue(2000).minOrderAmount(0).totalStock(100).remainStock(50)
                .perUserLimit(1).validDays(7).status("ACTIVE").build();
    }

    @Test
    @DisplayName("无 ACTIVE 场次 → 空列表，且不再查模板")
    void noActiveSessions_returnsEmpty() {
        when(seckillSessionMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<CouponTemplateVO> result = couponService.listActiveCoupons();

        assertThat(result).isEmpty();
        verify(couponTemplateMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("有场次但模板全部下线 → 空列表")
    void sessionsButNoActiveTemplates_returnsEmpty() {
        when(seckillSessionMapper.selectList(any())).thenReturn(List.of(session(1L, 1L)));
        when(couponTemplateMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<CouponTemplateVO> result = couponService.listActiveCoupons();

        assertThat(result).isEmpty();
        verify(shopMapper, never()).selectBatchIds(anyList());
    }

    @Test
    @DisplayName("完整路径：拼出 VO，并把门店名冗余进去")
    void fullPath_buildsVoWithShopName() {
        when(seckillSessionMapper.selectList(any())).thenReturn(List.of(session(1L, 1L)));
        when(couponTemplateMapper.selectList(any())).thenReturn(List.of(template(1L, 10L)));
        when(shopMapper.selectBatchIds(anyList()))
                .thenReturn(List.of(Shop.builder().id(10L).shopName("老王火锅").build()));

        List<CouponTemplateVO> result = couponService.listActiveCoupons();

        assertThat(result).hasSize(1);
        CouponTemplateVO vo = result.get(0);
        assertThat(vo.getTemplateId()).isEqualTo("1");
        assertThat(vo.getSessionId()).isEqualTo("1");
        assertThat(vo.getShopName()).isEqualTo("老王火锅");
        assertThat(vo.getCouponName()).isEqualTo("满100减20");
        assertThat(vo.getRemainStock()).isEqualTo(50);
    }

    @Test
    @DisplayName("门店查不到 → shopName 兜底空串，不抛异常")
    void missingShop_defaultsToEmptyShopName() {
        when(seckillSessionMapper.selectList(any())).thenReturn(List.of(session(1L, 1L)));
        when(couponTemplateMapper.selectList(any())).thenReturn(List.of(template(1L, 10L)));
        // 门店批量查询返回空（门店被删/数据不一致）
        when(shopMapper.selectBatchIds(anyList())).thenReturn(Collections.emptyList());

        List<CouponTemplateVO> result = couponService.listActiveCoupons();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getShopName()).isEmpty();
    }
}
