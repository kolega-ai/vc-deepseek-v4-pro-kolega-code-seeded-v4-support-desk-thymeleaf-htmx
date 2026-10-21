package com.relaydesk.channel.web;

import com.relaydesk.channel.service.RequestPrincipal;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@code @CurrentUser RequestPrincipal} from the {@code requestPrincipal} request
 * attribute set by {@link DecisionAuthFilter}. Resolves to {@code null} on public pages.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String REQUEST_PRINCIPAL_ATTR = "requestPrincipal";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && RequestPrincipal.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Object attr = webRequest.getAttribute(REQUEST_PRINCIPAL_ATTR,
                RequestAttributes.SCOPE_REQUEST);
        return attr instanceof RequestPrincipal p ? p : null;
    }
}
