package com.amicos.proxy;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.Objects;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.config.SocketConfig;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.HeaderGroup;
import org.apache.http.util.EntityUtils;

public class ProxyServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;
	private boolean doForwardIP = true;
	private boolean doSendUrlFragment = true;
	private boolean doPreserveHost = false;
	private boolean doHandleRedirects = false;
	private boolean useSystemProperties = true;
	private int connectTimeout = -1;
	private int readTimeout = -1;
	private int connectionRequestTimeout = -1;
	private int maxConnections = -1;

	private String targetUri;
	private URI targetUriObj;
	private HttpHost targetHost;

	private HttpClient proxyClient;

	private String getTargetUri(HttpServletRequest servletRequest) {
		return (String) servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_URI);
	}

	private HttpHost getTargetHost(HttpServletRequest servletRequest) {
		return (HttpHost) servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_HOST);
	}

	@Override
	public void init() throws ServletException {
		try {
			String serviceName = StringUtils.defaultIfEmpty(getConfigParam(ProxyConstants.SERVICE_NAME), "default");

			this.doForwardIP = BooleanUtils
					.toBoolean(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_FORWARDEDFOR), getConfigParam(ProxyConstants.P_FORWARDEDFOR)));

			this.doPreserveHost = BooleanUtils
					.toBoolean(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_PRESERVEHOST), getConfigParam(ProxyConstants.P_PRESERVEHOST)));

			this.doHandleRedirects = BooleanUtils
					.toBoolean(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_HANDLEREDIRECTS), getConfigParam(ProxyConstants.P_HANDLEREDIRECTS)));

			this.connectTimeout = NumberUtils
					.toInt(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_CONNECTTIMEOUT), getConfigParam(ProxyConstants.P_CONNECTTIMEOUT)), -1);

			this.readTimeout = NumberUtils
					.toInt(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_READTIMEOUT), getConfigParam(ProxyConstants.P_READTIMEOUT)), -1);

			this.readTimeout = NumberUtils.toInt(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_CONNECTIONREQUESTTIMEOUT),
					getConfigParam(ProxyConstants.P_CONNECTIONREQUESTTIMEOUT)), -1);

			this.maxConnections = NumberUtils
					.toInt(StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_MAXCONNECTIONS), getConfigParam(ProxyConstants.P_MAXCONNECTIONS)), -1);

			this.doForwardIP = BooleanUtils.toBoolean(
					StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_USESYSTEMPROPERTIES), getConfigParam(ProxyConstants.P_USESYSTEMPROPERTIES)));

			this.targetUri = StringUtils.defaultIfEmpty(LoadProxyConfig.getInstance().get(serviceName + "." + ProxyConstants.P_TARGET_URI), getConfigParam(ProxyConstants.P_TARGET_URI));

			if (Objects.isNull(targetUri)) {
				throw new ServletException(serviceName + "." + ProxyConstants.P_TARGET_URI + " is required.");
			}
			targetUriObj = new URI(targetUri);
		} catch (Exception e) {
			throw new ServletException("Trying to process targetUri init parameter: " + e, e);
		}
		targetHost = URIUtils.extractHost(targetUriObj);

		proxyClient = createHttpClient();
	}

	private String getConfigParam(String key) {
		return getServletConfig().getInitParameter(key);
	}

	private RequestConfig buildRequestConfig() {
		return RequestConfig.custom().setRedirectsEnabled(doHandleRedirects).setCookieSpec(CookieSpecs.IGNORE_COOKIES).setConnectTimeout(connectTimeout).setSocketTimeout(readTimeout)
				.setConnectionRequestTimeout(connectionRequestTimeout).build();
	}

	private SocketConfig buildSocketConfig() {

		if (readTimeout < 1) {
			return null;
		}
		return SocketConfig.custom().setSoTimeout(readTimeout).build();
	}

	private HttpClient createHttpClient() {
		HttpClientBuilder clientBuilder = HttpClientBuilder.create().setDefaultRequestConfig(buildRequestConfig()).setDefaultSocketConfig(buildSocketConfig());
		clientBuilder.setMaxConnTotal(maxConnections);
		if (useSystemProperties) {
			clientBuilder = clientBuilder.useSystemProperties();
		}
		return clientBuilder.build();
	}

	@Override
	public void destroy() {
		if (proxyClient instanceof Closeable) {
			try {
				((Closeable) proxyClient).close();
			} catch (IOException e) {
				System.err.println("Destroying servlet, shutting down HttpClient: " + e);
			}
		} else {
			if (proxyClient != null) {
				proxyClient = null;
			}
		}
		super.destroy();
	}

	@Override
	protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {
		HttpResponse proxyResponse = null;
		HttpRequest proxyRequest = null;
		try {
			if (Objects.isNull(servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_URI))) {
				servletRequest.setAttribute(ProxyConstants.ATTR_TARGET_URI, targetUri);
			}
			if (Objects.isNull(servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_HOST))) {
				servletRequest.setAttribute(ProxyConstants.ATTR_TARGET_HOST, targetHost);
			}

			String method = servletRequest.getMethod();
			String proxyRequestUri = rewriteUrlFromRequest(servletRequest);

			if (Objects.nonNull(servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH)) || Objects.nonNull(servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING))) {
				proxyRequest = newProxyRequestWithEntity(method, proxyRequestUri, servletRequest);
			} else {
				proxyRequest = new BasicHttpRequest(method, proxyRequestUri);
			}

			copyRequestHeaders(servletRequest, proxyRequest);

			setXForwardedForHeader(servletRequest, proxyRequest);

			proxyResponse = doExecute(servletRequest, servletResponse, proxyRequest);

			int statusCode = proxyResponse.getStatusLine().getStatusCode();

			servletResponse.setStatus(statusCode);
			copyResponseHeaders(proxyResponse, servletRequest, servletResponse);

			if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {

				servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
			} else {

				copyResponseEntity(proxyResponse, servletResponse, proxyRequest, servletRequest);
			}

		} catch (Exception e) {
			System.err.println(e.getMessage() + e);
			throw new RuntimeException(e);
		} finally {
			if (proxyResponse != null) {
				EntityUtils.consumeQuietly(proxyResponse.getEntity());
				proxyResponse = null;
			}
			proxyRequest = null;
		}
	}

	private HttpResponse doExecute(HttpServletRequest servletRequest, HttpServletResponse servletResponse, HttpRequest proxyRequest) throws IOException {
		System.out.println("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
		return proxyClient.execute(getTargetHost(servletRequest), proxyRequest);
	}

	private HttpRequest newProxyRequestWithEntity(String method, String proxyRequestUri, HttpServletRequest servletRequest) throws IOException {
		HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, proxyRequestUri);
		eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), getContentLength(servletRequest)));
		return eProxyRequest;
	}

	private long getContentLength(HttpServletRequest request) {
		String contentLengthHeader = request.getHeader("Content-Length");
		if (Objects.nonNull(contentLengthHeader)) {
			return Long.parseLong(contentLengthHeader);
		}
		return -1L;
	}

	private static final HeaderGroup hopByHopHeaders;
	static {
		hopByHopHeaders = new HeaderGroup();
		String[] headers = new String[] { "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization", "TE", "Trailers", "Transfer-Encoding", "Upgrade" };
		for (String header : headers) {
			hopByHopHeaders.addHeader(new BasicHeader(header, null));
		}
	}

	@SuppressWarnings("unchecked")
	private void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
		Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
		while (enumerationOfHeaderNames.hasMoreElements()) {
			String headerName = enumerationOfHeaderNames.nextElement();
			copyRequestHeader(servletRequest, proxyRequest, headerName);
		}
	}

	@SuppressWarnings("unchecked")
	private void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest, String headerName) {
		if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH)) {
			return;
		}
		if (hopByHopHeaders.containsHeader(headerName)) {
			return;
		}
		Enumeration<String> headers = servletRequest.getHeaders(headerName);
		while (headers.hasMoreElements()) {
			String headerValue = headers.nextElement();
			if (!doPreserveHost && headerName.equalsIgnoreCase(HttpHeaders.HOST)) {
				HttpHost host = getTargetHost(servletRequest);
				headerValue = host.getHostName();
				if (host.getPort() != -1)
					headerValue += ":" + host.getPort();
			}
			proxyRequest.addHeader(headerName, headerValue);
		}
	}

	private void setXForwardedForHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
		if (doForwardIP) {
			String forHeaderName = "X-Forwarded-For";
			String forHeader = servletRequest.getRemoteAddr();
			String existingForHeader = servletRequest.getHeader(forHeaderName);
			if (Objects.nonNull(existingForHeader)) {
				forHeader = existingForHeader + ", " + forHeader;
			}
			proxyRequest.setHeader(forHeaderName, forHeader);

			String protoHeaderName = "X-Forwarded-Proto";
			String protoHeader = servletRequest.getScheme();
			proxyRequest.setHeader(protoHeaderName, protoHeader);
		}
	}

	private void copyResponseHeaders(HttpResponse proxyResponse, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		for (Header header : proxyResponse.getAllHeaders()) {
			copyResponseHeader(servletRequest, servletResponse, header);
		}
	}

	private void copyResponseHeader(HttpServletRequest servletRequest, HttpServletResponse servletResponse, Header header) {
		String headerName = header.getName();
		if (hopByHopHeaders.containsHeader(headerName)) {
			return;
		}
		String headerValue = header.getValue();
		if (headerName.equalsIgnoreCase(HttpHeaders.LOCATION)) {
			servletResponse.addHeader(headerName, rewriteUrlFromResponse(servletRequest, headerValue));
		} else {
			servletResponse.addHeader(headerName, headerValue);
		}
	}

	private void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse, HttpRequest proxyRequest, HttpServletRequest servletRequest) throws IOException {
		HttpEntity entity = proxyResponse.getEntity();
		if (Objects.nonNull(entity)) {
			OutputStream servletOutputStream = servletResponse.getOutputStream();
			entity.writeTo(servletOutputStream);
		}
	}

	private String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
		StringBuilder uri = new StringBuilder();
		uri.append(getTargetUri(servletRequest));
		String pathInfo = rewritePathInfoFromRequest(servletRequest);

		if (Objects.nonNull(pathInfo)) {
			uri.append(encodeUriQuery(pathInfo, true));
		}

		String queryString = servletRequest.getQueryString();
		String fragment = null;

		if (Objects.nonNull(queryString)) {
			int fragIdx = queryString.indexOf('#');
			if (fragIdx >= 0) {
				fragment = queryString.substring(fragIdx + 1);
				queryString = queryString.substring(0, fragIdx);
			}
		}

		queryString = rewriteQueryStringFromRequest(servletRequest, queryString);
		if (Objects.nonNull(queryString) && queryString.length() > 0) {
			uri.append('?');
			uri.append(encodeUriQuery(queryString, false));
		}

		if (doSendUrlFragment && Objects.nonNull(fragment)) {
			uri.append('#');
			uri.append(encodeUriQuery(fragment, false));
		}
		return uri.toString();
	}

	private String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
		return queryString;
	}

	private String rewritePathInfoFromRequest(HttpServletRequest servletRequest) {
		return servletRequest.getPathInfo();
	}

	private String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl) {
		final String targetUri = getTargetUri(servletRequest);
		if (theUrl.startsWith(targetUri)) {
			StringBuffer curUrl = servletRequest.getRequestURL();// no query
			int pos;
			if ((pos = curUrl.indexOf("://")) >= 0) {
				if ((pos = curUrl.indexOf("/", pos + 3)) >= 0) {
					curUrl.setLength(pos);
				}
			}
			curUrl.append(servletRequest.getContextPath());
			curUrl.append(servletRequest.getServletPath());
			curUrl.append(theUrl, targetUri.length(), theUrl.length());
			return curUrl.toString();
		}
		return theUrl;
	}

	public String getTargetUri() {
		return targetUri;
	}

	private CharSequence encodeUriQuery(CharSequence in, boolean encodePercent) {

		StringBuilder outBuf = null;
		Formatter formatter = null;
		for (int i = 0; i < in.length(); i++) {
			char c = in.charAt(i);
			boolean escape = true;
			if (c < 128) {
				if (asciiQueryChars.get((int) c) && !(encodePercent && c == '%')) {
					escape = false;
				}
			} else if (!Character.isISOControl(c) && !Character.isSpaceChar(c)) {// not-ascii
				escape = false;
			}
			if (!escape) {
				if (outBuf != null)
					outBuf.append(c);
			} else {
				// escape
				if (outBuf == null) {
					outBuf = new StringBuilder(in.length() + 5 * 3);
					outBuf.append(in, 0, i);
					formatter = new Formatter(outBuf);
				}
				formatter.format("%%%02X", (int) c);// TODO
			}
		}
		return outBuf != null ? outBuf : in;
	}

	private static final BitSet asciiQueryChars;
	static {
		char[] c_unreserved = "_-!.~'()*".toCharArray();
		char[] c_punct = ",;:$&+=".toCharArray();
		char[] c_reserved = "?/[]@".toCharArray();

		asciiQueryChars = new BitSet(128);
		for (char c = 'a'; c <= 'z'; c++)
			asciiQueryChars.set((int) c);
		for (char c = 'A'; c <= 'Z'; c++)
			asciiQueryChars.set((int) c);
		for (char c = '0'; c <= '9'; c++)
			asciiQueryChars.set((int) c);
		for (char c : c_unreserved)
			asciiQueryChars.set((int) c);
		for (char c : c_punct)
			asciiQueryChars.set((int) c);
		for (char c : c_reserved)
			asciiQueryChars.set((int) c);

		asciiQueryChars.set((int) '%');
	}

}
