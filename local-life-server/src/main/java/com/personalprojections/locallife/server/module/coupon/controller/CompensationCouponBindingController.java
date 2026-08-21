package com.personalprojections.locallife.server.module.coupon.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.coupon.dto.CompensationCouponBindingRequest;
import com.personalprojections.locallife.server.module.coupon.dto.CompensationCouponBindingVO;
import com.personalprojections.locallife.server.module.coupon.service.CompensationCouponBindingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Merchant management endpoints for shop-scoped compensation coupon bindings. */
@RestController
@RequestMapping("/api/v1/shops/{shopId}/compensation-coupon-bindings")
@RequiredArgsConstructor
public class CompensationCouponBindingController {

    private final CompensationCouponBindingService service;

    @GetMapping
    public Result<List<CompensationCouponBindingVO>> list(@PathVariable long shopId) {
        return Result.ok(service.list(shopId));
    }

    @GetMapping("/{faceValueMinor}")
    public Result<CompensationCouponBindingVO> get(
            @PathVariable long shopId,
            @PathVariable int faceValueMinor
    ) {
        return Result.ok(service.get(shopId, faceValueMinor));
    }

    @PutMapping("/{faceValueMinor}")
    public Result<CompensationCouponBindingVO> upsert(
            @PathVariable long shopId,
            @PathVariable int faceValueMinor,
            @Valid @RequestBody CompensationCouponBindingRequest request
    ) {
        return Result.ok(service.upsert(
                shopId, faceValueMinor, request.getCouponTemplateId()));
    }

    @PutMapping("/{faceValueMinor}/status/disabled")
    public Result<CompensationCouponBindingVO> disable(
            @PathVariable long shopId,
            @PathVariable int faceValueMinor
    ) {
        return Result.ok(service.disable(shopId, faceValueMinor));
    }
}
