package com.yu030x.booking.log.wiring;

import com.yu030x.booking.log.annotation.OperationLog;
import com.yu030x.booking.log.interceptor.OperationLogInterceptor;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.AopUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.util.ReflectionUtils;

/**
 * Point-targeted proxy wiring: only beans that declare a method annotated with
 * {@link OperationLog} are wrapped (or joined on an existing unfrozen proxy)
 * with the operation-log advice. No package-wide or controller-wide
 * interception, no wrapping of this slice's own infrastructure (recursion
 * safety), and no duplicate advice on already-advised proxies.
 */
public class OperationLogWiringPostProcessor implements BeanPostProcessor {

    private static final String OWN_PACKAGE = "com.yu030x.booking.log.";

    private final OperationLogInterceptor interceptor;

    public OperationLogWiringPostProcessor(OperationLogInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean == null || bean.getClass().getName().startsWith(OWN_PACKAGE)) {
            return bean;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (!declaresAnnotatedMethod(targetClass)) {
            return bean;
        }
        if (bean instanceof Advised advised && !advised.isFrozen()) {
            if (alreadyAdvised(advised)) {
                return bean;
            }
            advised.addAdvice(interceptor);
            return bean;
        }
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        return factory.getProxy(bean.getClass().getClassLoader());
    }

    private boolean alreadyAdvised(Advised advised) {
        for (Advisor advisor : advised.getAdvisors()) {
            if (advisor.getAdvice() instanceof OperationLogInterceptor) {
                return true;
            }
        }
        return false;
    }

    private boolean declaresAnnotatedMethod(Class<?> type) {
        boolean[] found = new boolean[1];
        ReflectionUtils.doWithMethods(type, method -> found[0] = true,
                method -> !method.isBridge()
                        && !method.getDeclaringClass().getName().startsWith("java.")
                        && method.isAnnotationPresent(OperationLog.class));
        return found[0];
    }
}
