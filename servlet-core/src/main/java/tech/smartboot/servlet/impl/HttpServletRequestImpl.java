/*
 *  Copyright (C) [2022] smartboot [zhengjunweimail@163.com]
 *
 *  企业用户未经smartboot组织特别许可，需遵循AGPL-3.0开源协议合理合法使用本项目。
 *
 *   Enterprise users are required to use this project reasonably
 *   and legally in accordance with the AGPL-3.0 open source agreement
 *  without special permission from the smartboot organization.
 */

package tech.smartboot.servlet.impl;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestAttributeEvent;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.PushBuilder;
import tech.smartboot.feat.core.common.FeatUtils;
import tech.smartboot.feat.core.common.HeaderName;
import tech.smartboot.feat.core.common.HeaderValue;
import tech.smartboot.feat.core.common.logging.Logger;
import tech.smartboot.feat.core.common.logging.LoggerFactory;
import tech.smartboot.feat.core.common.multipart.MultipartConfig;
import tech.smartboot.feat.core.server.HttpRequest;
import tech.smartboot.feat.core.server.HttpResponse;
import tech.smartboot.feat.core.server.impl.Upgrade;
import tech.smartboot.servlet.ServletContextRuntime;
import tech.smartboot.servlet.SmartHttpServletRequest;
import tech.smartboot.servlet.conf.ServletInfo;
import tech.smartboot.servlet.conf.ServletMappingInfo;
import tech.smartboot.servlet.plugins.security.LoginAccount;
import tech.smartboot.servlet.plugins.security.SecurityAccount;
import tech.smartboot.servlet.provider.SessionProvider;
import tech.smartboot.servlet.util.DateUtil;

import javax.net.ssl.SSLEngine;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.UnsupportedCharsetException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * @author 三刀
 * @version V1.0 , 2019/12/11
 */
public class HttpServletRequestImpl implements SmartHttpServletRequest {
    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServletRequestImpl.class);
    private static final String URL_JSESSION_ID = ";" + SessionProvider.DEFAULT_SESSION_PARAMETER_NAME + "=";
    private static final Cookie[] NONE_COOKIE = new Cookie[0];
    private final HttpRequest request;
    private ServletContextImpl servletContext;
    private final DispatcherType dispatcherType = DispatcherType.REQUEST;
    private final ServletContextRuntime runtime;
    private Charset characterEncoding;
    private Map<String, Object> attributes;
    private Cookie[] cookies;
    private String servletPath;
    private String pathInfo;
    private String requestUri;
    private HttpServletResponse httpServletResponse;
    private ServletInputStream servletInputStream;
    private BufferedReader reader;
    private boolean pathInit = false;
    /**
     * 请求中携带的sessionId
     */
    private String requestedSessionId;


    private String actualSessionId;

    /**
     * sessionId是否来源于Cookie
     */
    private boolean sessionIdFromCookie;

    /**
     * 匹配的Servlet
     */
    private ServletInfo servletInfo;

    private boolean asyncStarted = false;

    private boolean asyncSupported = true;

    private volatile AsyncContext asyncContext = null;
    private final CompletableFuture<Void> completableFuture;

    private ServletMappingInfo servletMappingInfo;
    private LoginAccount principal;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;


    public HttpServletRequestImpl(HttpRequest request, ServletContextRuntime runtime, CompletableFuture<Void> completableFuture) {
        this.request = request;
        this.servletContext = runtime.getServletContext();
        this.runtime = runtime;
        this.completableFuture = completableFuture;
        int index = request.getRequestURI().indexOf(URL_JSESSION_ID);
        if (index == -1) {
            this.requestUri = request.getRequestURI();
        } else {
            this.requestUri = request.getRequestURI().substring(0, index);
            this.requestedSessionId = request.getRequestURI().substring(index + URL_JSESSION_ID.length());
        }
        this.remoteAddress = request.getRemoteAddress();
        this.localAddress = request.getLocalAddress();
    }

    public void setHttpServletResponse(HttpServletResponse httpServletResponse) {
        this.httpServletResponse = httpServletResponse;
    }

    @Override
    public String getAuthType() {
        servletContext.log("unSupport getAuthType");
        return principal == null ? null : principal.getAuthType();
    }

    @Override
    public Cookie[] getCookies() {
        if (cookies != null) {
            return cookies == NONE_COOKIE ? null : cookies;
        }
        tech.smartboot.feat.core.common.Cookie[] cookie = request.getCookies();
        if (cookie == null || cookie.length == 0) {
            cookies = NONE_COOKIE;
        } else {
            List<Cookie> list = new ArrayList<>(cookie.length);
            for (tech.smartboot.feat.core.common.Cookie value : cookie) {
                if ("Path".equals(value.getName())) {
                    LOGGER.warn("invalid cookie name: " + value.getName());
                    continue;
                }
                list.add(new Cookie(value.getName(), value.getValue()));
            }
            cookies = new Cookie[list.size()];
            list.toArray(cookies);
        }
        return getCookies();
    }

    @Override
    public long getDateHeader(String name) {
        String value = this.getHeader(name);
        if (value == null) {
            return -1L;
        } else {
            return DateUtil.parseDateHeader(name, value);
        }
    }

    @Override
    public String getHeader(String name) {
        return request.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        return Collections.enumeration(request.getHeaders(name));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(request.getHeaderNames());
    }

    @Override
    public int getIntHeader(String name) {
        String strVal = getHeader(name);
        //If the request does not have a header of the specified name, this method returns -1.
        if (strVal == null) {
            return -1;
        }
        return Integer.parseInt(strVal);
    }

    @Override
    public String getMethod() {
        return request.getMethod();
    }

    @Override
    public String getPathInfo() {
        initPath();
        return pathInfo;
    }

    @Override
    public void setServletInfo(ServletInfo servletInfo) {
        this.servletInfo = servletInfo;
        if (asyncSupported) {
            asyncSupported = servletInfo.isAsyncSupported();
        }
    }

    @Override
    public ServletInfo getServletInfo() {
        return servletInfo;
    }

    @Override
    public String getPathTranslated() {
        return servletContext.getRealPath(getPathInfo());
    }

    @Override
    public String getContextPath() {
        return servletContext.getContextPath();
    }

    @Override
    public String getQueryString() {
        return request.getQueryString();
    }

    @Override
    public String getRemoteUser() {
        Principal principal = getUserPrincipal();
        return principal == null ? null : principal.getName();
    }

    @Override
    public boolean isUserInRole(String role) {
        return runtime.getSecurityProvider().isUserInRole(role, principal, this);
    }

    @Override
    public Principal getUserPrincipal() {
        if (principal != null) {
            return principal;
        }
        HttpSession session = getSession(false);
        if (session == null) {
            return null;
        }
        Object o = session.getAttribute("principal");
        if (o != null) {
            principal = (LoginAccount) o;
        }
        return principal;
    }

    @Override
    public String getRequestedSessionId() {
        if (requestedSessionId != null) {
            return FeatUtils.EMPTY.equals(requestedSessionId) ? null : requestedSessionId;
        }
        Cookie[] cookies = getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (SessionProvider.DEFAULT_SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    requestedSessionId = cookie.getValue();
                    sessionIdFromCookie = true;
                    break;
                }
            }
        }
        if (FeatUtils.isBlank(requestedSessionId)) {
            requestedSessionId = FeatUtils.EMPTY;
        }
        return getRequestedSessionId();
    }

    public void setActualSessionId(String sessionId) {
        this.actualSessionId = sessionId;
    }

    public String getActualSessionId() {
        return actualSessionId;
    }

    @Override
    public String getRequestURI() {
        return requestUri;
    }

    @Override
    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
        servletPath = null;
    }

    @Override
    public StringBuffer getRequestURL() {
        return new StringBuffer(request.getRequestURL());
    }

    @Override
    public String getServletPath() {
        initPath();
        return servletPath;
    }

    private void initPath() {
        if (pathInit) {
            return;
        }
        pathInit = true;
        switch (servletMappingInfo.getMappingMatch()) {
            case DEFAULT: {
                //12.2 空字符串“”是一个特殊的 URL 模式，其精确映射到应用的上下文根，即，http://host:port/<context-root>/请求形式。
                // 在这种情况下，路径信息是‘/’且 servlet 路径和上下文路径是空字符串（“”）。
                servletPath = "";
                if (getContextPath().length() + servletPath.length() < getRequestURI().length()) {
                    pathInfo = getRequestURI().substring(getContextPath().length());
                }
                break;
            }
            case EXACT:
                servletPath = servletMappingInfo.getUrlPattern();
                pathInfo = null;
                break;
            case EXTENSION: {
                servletPath = getRequestURI().substring(getContextPath().length());
                pathInfo = null;
                break;
            }
            case PATH: {
                servletPath = servletMappingInfo.getUrlPattern().substring(0, servletMappingInfo.getUrlPattern().length() - 2);
                if (getContextPath().length() + servletPath.length() < getRequestURI().length()) {
                    pathInfo = getRequestURI().substring(getContextPath().length() + servletPath.length());
                }
                break;
            }
        }
    }

    @Override
    public HttpSession getSession(boolean create) {
        return runtime.getSessionProvider().getSession(this, httpServletResponse, create);
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        HttpSession session = getSession(false);
        if (session == null) {
            throw new IllegalStateException();
        }
        runtime.getSessionProvider().changeSessionId(session);
        setActualSessionId(session.getId());
        return session.getId();
    }

    @Override
    public boolean isRequestedSessionIdValid() {
        return runtime.getSessionProvider().isRequestedSessionIdValid(this);
    }

    @Override
    public boolean isRequestedSessionIdFromCookie() {
        return getRequestedSessionId() != null && sessionIdFromCookie;
    }

    @Override
    public boolean isRequestedSessionIdFromURL() {
        return getRequestedSessionId() != null && !sessionIdFromCookie;
    }


    @Override
    public boolean authenticate(HttpServletResponse response) {
        return principal != null;
    }

    @Override
    public void login(String username, String password) throws ServletException {
        SecurityAccount securityAccount = runtime.getSecurityProvider().login(username, password);
        if (securityAccount != null) {
            setLoginAccount(new LoginAccount(securityAccount.getUsername(), securityAccount.getPassword(), securityAccount.getRoles(), HttpServletRequest.FORM_AUTH));
        }
    }

    @Override
    public HttpServletMapping getHttpServletMapping() {
        if (servletMappingInfo == null) {
            return null;
        }
        String matchValue;
        MappingMatch mappingMatch = servletMappingInfo.getMappingMatch();
        switch (servletMappingInfo.getMappingMatch()) {
            case DEFAULT:
                matchValue = "";
                if (FeatUtils.isBlank(servletContext.getContextPath())) {
                    mappingMatch = MappingMatch.CONTEXT_ROOT;
                }
                break;
            case EXACT:
                matchValue = servletMappingInfo.getUrlPattern();
                if (matchValue.startsWith("/")) {
                    matchValue = matchValue.substring(1);
                }
                break;
            case PATH:
                String servletPath = getServletPath();
                if (servletMappingInfo.getUrlPattern().length() >= servletPath.length() + 2) {
                    matchValue = "";
                } else {
                    matchValue = getServletPath().substring(servletMappingInfo.getUrlPattern().length() - 1);
                }

                if (matchValue.startsWith("/")) {
                    matchValue = matchValue.substring(1);
                }
                break;
            case EXTENSION:
                matchValue = getServletPath().substring(getServletPath().charAt(0) == '/' ? 1 : 0, getServletPath().length() - servletMappingInfo.getUrlPattern().length() + 1);
                break;
            default:
                throw new IllegalStateException();
        }
        return new HttpServletMappingImpl(mappingMatch, servletMappingInfo, matchValue);
    }

    public void setServletMappingInfo(ServletMappingInfo servletMappingInfo) {
        this.servletMappingInfo = servletMappingInfo;
    }

    @Override
    public ServletMappingInfo getServletMappingInfo() {
        return servletMappingInfo;
    }

    @Override
    public void logout() {
        getSession().removeAttribute("principal");
        principal = null;
    }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        parseParts();
        if (partsParseException != null) {
            if (partsParseException instanceof IOException) {
                throw (IOException) partsParseException;
            } else if (partsParseException instanceof ServletException) {
                throw (ServletException) partsParseException;
            }
        }
        return parts;
    }

    private Collection<Part> parts = null;
    private Exception partsParseException = null;

    private void parseParts() throws ServletException {
        if (parts != null || partsParseException != null) {
            return;
        }
        if (!request.getContentType().startsWith(HeaderValue.ContentType.MULTIPART_FORM_DATA)) {
            throw new ServletException("Not a multipart request");
        }
        try {
            MultipartConfigElement multipartConfigElement = servletInfo.getMultipartConfig();
            //获取文件存放目录
            File location = getLocation(multipartConfigElement);
            if (!location.isDirectory()) {
                throw new IOException("there's no upload-file directory!");
            }
            MultipartConfig config = new MultipartConfig(location.getAbsolutePath(), multipartConfigElement.getMaxFileSize(), multipartConfigElement.getMaxRequestSize(),
                    multipartConfigElement.getFileSizeThreshold());
            parts = new ArrayList<>();

            Collection<tech.smartboot.feat.core.common.multipart.Part> items = request.getParts(config);
            for (tech.smartboot.feat.core.common.multipart.Part item : items) {
                PartImpl part = new PartImpl(item, location);
                parts.add(part);
                if (part.getSubmittedFileName() == null) {
                    String name = part.getName();
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    part.getInputStream().transferTo(byteArrayOutputStream);
                    String value = byteArrayOutputStream.toString();
                    request.getParameters().put(name, new String[]{value});
                }
            }
        } catch (IOException e) {
            partsParseException = e;
        }
    }

    private File getLocation(MultipartConfigElement multipartConfigElement) {
        File location;
        String locationStr = multipartConfigElement.getLocation();
        //未指定location，采用临时目录
        if (FeatUtils.isBlank(locationStr)) {
            location = ((File) servletContext.getAttribute(ServletContext.TEMPDIR));
        } else {
            location = new File(locationStr);
            //非绝对路径，则存放于临时目录下
            if (!location.isAbsolute()) {
                location = new File((File) servletContext.getAttribute(ServletContext.TEMPDIR), locationStr).getAbsoluteFile();
            }
        }
        if (!location.exists()) {
            LOGGER.warn("create upload-file directory：{}", location.getAbsolutePath());
            if (!location.mkdirs()) {
                LOGGER.warn("fail to create upload-file directory,{}", location.getAbsolutePath());
            }
        }
        return location;
    }

    @Override
    public Map<String, String> getTrailerFields() {
        return request.getTrailerFields();
    }

    @Override
    public boolean isTrailerFieldsReady() {
        return request.isTrailerFieldsReady();
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        for (Part part : getParts()) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }

    @Override
    public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) throws IOException, ServletException {
        T t = null;
        try {
            t = handlerClass.newInstance();
            t.init(new WebConnectionImpl(request) {
                @Override
                public void close() throws Exception {

                }

                @Override
                public ServletInputStream getInputStream() throws IOException {
                    return new UpgradeServletInputStream(HttpServletRequestImpl.this.request.getInputStream());
                }

                @Override
                public ServletOutputStream getOutputStream() throws IOException {
                    return httpServletResponse.getOutputStream();
                }
            });
            request.upgrade(new Upgrade() {

                @Override
                public void init(HttpRequest request, HttpResponse response) throws IOException {
                    System.out.println("init...");
                }

                @Override
                public void onBodyStream(ByteBuffer buffer) {
                    System.out.println("onBodyStream...");
                    if (request.getInputStream().getReadListener() != null) {
                        try {
                            request.getInputStream().getReadListener().onDataAvailable();
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            });
        } catch (Exception e) {
            throw new ServletException(e);
        }
        return t;
    }

    @Override
    public Object getAttribute(String name) {
        return attributes == null ? null : attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes == null ? new ArrayList<String>(0) : attributes.keySet());
    }

    @Override
    public String getCharacterEncoding() {
        if (characterEncoding != null) {
            return characterEncoding.name();
        }
        String value = getHeader(HeaderName.CONTENT_TYPE.getName());
        String charset = FeatUtils.substringAfter(value, "charset=");
        if (FeatUtils.isNotBlank(charset)) {
            return charset;
        }
        return runtime.getServletContext().getRequestCharacterEncoding();
    }

    @Override
    public void setCharacterEncoding(String characterEncoding) throws UnsupportedEncodingException {
        if (servletInputStream != null) {
            return;
        }
        try {
            this.characterEncoding = Charset.forName(characterEncoding);
        } catch (UnsupportedCharsetException e) {
            throw new UnsupportedEncodingException();
        }

    }

    @Override
    public int getContentLength() {
        return (int) request.getContentLength();
    }

    @Override
    public long getContentLengthLong() {
        return request.getContentLength();
    }

    @Override
    public String getContentType() {
        return request.getContentType();
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (reader != null) {
            throw new IllegalStateException("getReader method has already been called for this request");
        }
        if (servletInputStream == null) {
            servletInputStream = new ServletInputStreamImpl(this, request.getInputStream());
        }
        return servletInputStream;
    }

    @Override
    public String getParameter(String name) {
        String value = request.getParameter(name);
        if (value == null && request.getContentType() != null && request.getContentType().startsWith(HeaderValue.ContentType.MULTIPART_FORM_DATA)) {
            try {
                parseParts();
            } catch (ServletException e) {
                throw new RuntimeException(e);
            }
        }
        return request.getParameter(name);
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(request.getParameters().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        return request.getParameterValues(name);
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return request.getParameters();
    }

    @Override
    public String getProtocol() {
        return request.getProtocol().getProtocol();
    }

    @Override
    public String getScheme() {
        return request.getScheme();
    }

    @Override
    public String getServerName() {
        String host = getHeader(HeaderName.HOST.getName());
        if (FeatUtils.isBlank(host)) {
            return localAddress.getHostName();
        }
        int index = host.indexOf(":");
        if (index < 0) {
            return host;
        } else {
            return host.substring(0, index);
        }
    }

    @Override
    public int getServerPort() {
        String host = getHeader(HeaderName.HOST.getName());
        if (FeatUtils.isBlank(host)) {
            throw new UnsupportedOperationException();
        }
        int index = host.indexOf(":");
        if (index < 0) {
            return localAddress.getPort();
        } else {
            return FeatUtils.toInt(host.substring(index + 1), -1);
        }
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (reader == null) {
            if (servletInputStream != null) {
                throw new IllegalStateException("getInputStream method has been called on this request");
            }
            String character = getCharacterEncoding();
            if (FeatUtils.isBlank(character)) {
                reader = new BufferedReader(new InputStreamReader(getInputStream()));
            } else {
                reader = new BufferedReader(new InputStreamReader(getInputStream(), character));
            }
        }
        return reader;
    }

    @Override
    public String getRemoteAddr() {
        return getAddress(remoteAddress);
    }

    @Override
    public String getRemoteHost() {
        return remoteAddress.getHostString();
    }

    @Override
    public void setAttribute(String name, Object o) {
        if (attributes == null) {
            attributes = new HashMap<>();
        }

        Object replace = attributes.put(name, o);
        if (FeatUtils.isNotEmpty(runtime.getDeploymentInfo().getRequestAttributeListeners())) {
            if (replace == null) {
                ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(servletContext, this, name, o);
                runtime.getDeploymentInfo().getRequestAttributeListeners().forEach(request -> request.attributeAdded(event));
            } else {
                ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(servletContext, this, name, replace);
                runtime.getDeploymentInfo().getRequestAttributeListeners().forEach(request -> request.attributeReplaced(event));
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        if (attributes == null) {
            return;
        }
        Object o = attributes.remove(name);
        if (FeatUtils.isNotEmpty(runtime.getDeploymentInfo().getRequestAttributeListeners())) {
            ServletRequestAttributeEvent event = new ServletRequestAttributeEvent(servletContext, this, name, o);
            runtime.getDeploymentInfo().getRequestAttributeListeners().forEach(request -> request.attributeRemoved(event));
        }
    }

    @Override
    public Locale getLocale() {
        return request.getLocale();
    }

    @Override
    public Enumeration<Locale> getLocales() {
        return request.getLocales();
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        return runtime.getDispatcherProvider().getRequestDispatcher(this, path);
    }


    @Override
    public int getRemotePort() {
        return remoteAddress.getPort();
    }

    @Override
    public String getLocalName() {
        return localAddress.getHostString();
    }

    @Override
    public String getLocalAddr() {
        return getAddress(localAddress);
    }

    private String getAddress(InetSocketAddress inetSocketAddress) {
        if (inetSocketAddress == null) {
            return "";
        }
        return inetSocketAddress.getAddress() == null ? inetSocketAddress.getHostString() : inetSocketAddress.getAddress().getHostAddress();
    }

    @Override
    public int getLocalPort() {
        return localAddress.getPort();
    }

    public void setServletContext(ServletContextImpl servletContext) {
        this.servletContext = servletContext;
    }

    @Override
    public ServletContextImpl getServletContext() {
        return servletContext;
    }

    @Override
    public AsyncContext startAsync() throws IllegalStateException {
        return startAsync(this, httpServletResponse);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) throws IllegalStateException {
        if (!isAsyncSupported()) {
            throw new IllegalStateException();
        } else if (asyncStarted) {
            throw new IllegalStateException();
        }
        asyncStarted = true;
        asyncContext = runtime.getAsyncContextProvider().startAsync(this, servletRequest, servletResponse, asyncContext);
        return asyncContext;
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncStarted;
    }

    public void resetAsyncStarted() {
        asyncStarted = false;
    }


    @Override
    public boolean isAsyncSupported() {
        return asyncSupported;
    }

    @Override
    public void setAsyncSupported(boolean supported) {
        this.asyncSupported = asyncStarted;
    }

    @Override
    public void setLoginAccount(LoginAccount loginAccount) {
        this.principal = loginAccount;
        getSession().setAttribute("principal", loginAccount);
    }

    @Override
    public SSLEngine getSslEngine() {
        return request.getSslEngine();
    }

    @Override
    public AsyncContext getAsyncContext() {
        if (isAsyncStarted()) {
            return asyncContext;
        }
        throw new IllegalStateException();
    }

    public AsyncContext getInternalAsyncContext() {
        return asyncContext;
    }

    @Override
    public DispatcherType getDispatcherType() {
        return dispatcherType;
    }

    @Override
    public String getRequestId() {
        return "";
    }

    @Override
    public String getProtocolRequestId() {
        return "";
    }

    @Override
    public ServletConnection getServletConnection() {
        return null;
    }

    public CompletableFuture<Void> getCompletableFuture() {
        return completableFuture;
    }

    @Override
    public PushBuilder newPushBuilder() {
        tech.smartboot.feat.core.server.PushBuilder pushBuilder = request.newPushBuilder();
        if (pushBuilder == null) {
            return null;
        }
        String sessionId;
        HttpSession session = getSession(false);
        if (session != null) {
            sessionId = session.getId();
        } else {
            sessionId = getRequestedSessionId();
        }
        if (sessionId != null) {
            pushBuilder.addHeader(HeaderName.COOKIE.getName(), "JSESSIONID=" + sessionId);
        }
        return new PushBuilder() {
            @Override
            public PushBuilder method(String method) {
                pushBuilder.method(method);
                return this;
            }

            @Override
            public PushBuilder queryString(String queryString) {
                pushBuilder.queryString(queryString);
                return this;
            }

            @Override
            public PushBuilder sessionId(String sessionId) {
                throw new IllegalStateException();
            }

            @Override
            public PushBuilder setHeader(String name, String value) {
                pushBuilder.setHeader(name, value);
                return this;
            }

            @Override
            public PushBuilder addHeader(String name, String value) {
                pushBuilder.addHeader(name, value);
                return this;
            }

            @Override
            public PushBuilder removeHeader(String name) {
                pushBuilder.removeHeader(name);
                return this;
            }

            @Override
            public PushBuilder path(String path) {
                pushBuilder.path(getContextPath() + "/" + path);
                return this;
            }

            @Override
            public void push() {
                pushBuilder.push();
            }

            @Override
            public String getMethod() {
                return pushBuilder.getMethod();
            }

            @Override
            public String getQueryString() {
                return pushBuilder.getQueryString();
            }

            @Override
            public String getSessionId() {
                return sessionId;
            }

            @Override
            public Set<String> getHeaderNames() {
                return pushBuilder.getHeaderNames();
            }

            @Override
            public String getHeader(String name) {
                return pushBuilder.getHeader(name);
            }

            @Override
            public String getPath() {
                return pushBuilder.getPath();
            }
        };
//        throw new UnsupportedOperationException();
    }
}
