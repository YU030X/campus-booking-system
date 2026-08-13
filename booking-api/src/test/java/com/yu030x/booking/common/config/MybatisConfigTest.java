package com.yu030x.booking.common.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MybatisConfigTest {
    @Test
    void configuresMysqlPaginationBounds() {
        MybatisPlusInterceptor interceptor = new MybatisConfig().mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors()).singleElement()
                .isInstanceOf(PaginationInnerInterceptor.class);
        PaginationInnerInterceptor pagination = (PaginationInnerInterceptor) interceptor.getInterceptors().get(0);
        assertThat(pagination.getMaxLimit()).isEqualTo(100L);
        assertThat(pagination.isOverflow()).isFalse();
    }
}
