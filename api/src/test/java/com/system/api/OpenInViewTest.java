package com.system.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spring.jpa.open-in-view is off: no Hibernate session stays open across a web
 * request. Every response is built inside the service's transaction, so nothing
 * needs it, and leaving it on would allow hidden database access after the service
 * returns. Spring Boot enables it by default (and warns about it at startup).
 */
class OpenInViewTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void noSessionIsHeldOpenAcrossWebRequests() {
        assertThat(context.getBeansOfType(OpenEntityManagerInViewInterceptor.class)).isEmpty();
    }
}
