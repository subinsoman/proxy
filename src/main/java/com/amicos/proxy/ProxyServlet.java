package com.amicos.proxy;


import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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

		String doForwardIPString = getConfigParam(ProxyConstants.P_FORWARDEDFOR);
		if (doForwardIPString != null) {
			this.doForwardIP = Boolean.parseBoolean(doForwardIPString);
		}

		String preserveHostString = getConfigParam(ProxyConstants.P_PRESERVEHOST);
		if (preserveHostString != null) {
			this.doPreserveHost = Boolean.parseBoolean(preserveHostString);
		}

		String handleRedirectsString = getConfigParam(ProxyConstants.P_HANDLEREDIRECTS);
		if (handleRedirectsString != null) {
			this.doHandleRedirects = Boolean.parseBoolean(handleRedirectsString);
		}

		String connectTimeoutString = getConfigParam(ProxyConstants.P_CONNECTTIMEOUT);
		if (connectTimeoutString != null) {
			this.connectTimeout = Integer.parseInt(connectTimeoutString);
		}

		String readTimeoutString = getConfigParam(ProxyConstants.P_READTIMEOUT);
		if (readTimeoutString != null) {
			this.readTimeout = Integer.parseInt(readTimeoutString);
		}

		String connectionRequestTimeout = getConfigParam(ProxyConstants.P_CONNECTIONREQUESTTIMEOUT);
		if (connectionRequestTimeout != null) {
			this.connectionRequestTimeout = Integer.parseInt(connectionRequestTimeout);
		}

		String maxConnections = getConfigParam(ProxyConstants.P_MAXCONNECTIONS);
		if (maxConnections != null) {
			this.maxConnections = Integer.parseInt(maxConnections);
		}

		String useSystemPropertiesString = getConfigParam(ProxyConstants.P_USESYSTEMPROPERTIES);
		if (useSystemPropertiesString != null) {
			this.useSystemProperties = Boolean.parseBoolean(useSystemPropertiesString);
		}

		initTarget();

		proxyClient = createHttpClient();
	}

	/**
	 * Reads a configuration parameter. By default it reads servlet init parameters
	 * but it can be overridden.
	 */
	private String getConfigParam(String key) {
		return getServletConfig().getInitParameter(key);
	}

	
	private RequestConfig buildRequestConfig() {
		return RequestConfig.custom().setRedirectsEnabled(doHandleRedirects).setCookieSpec(CookieSpecs.IGNORE_COOKIES) 
				.setConnectTimeout(connectTimeout).setSocketTimeout(readTimeout).setConnectionRequestTimeout(connectionRequestTimeout).build();
	}

	
	private SocketConfig buildSocketConfig() {

		if (readTimeout < 1) {
			return null;
		}

		return SocketConfig.custom().setSoTimeout(readTimeout).build();
	}

	private void initTarget() throws ServletException {
		targetUri = getConfigParam(ProxyConstants.P_TARGET_URI);
		if (targetUri == null)
			throw new ServletException(ProxyConstants.P_TARGET_URI + " is required.");
		// test it's valid
		try {
			targetUriObj = new URI(targetUri);
		} catch (Exception e) {
			throw new ServletException("Trying to process targetUri init parameter: " + e, e);
		}
		targetHost = URIUtils.extractHost(targetUriObj);
	}


	private HttpClient createHttpClient() {
		HttpClientBuilder clientBuilder = HttpClientBuilder.create().setDefaultRequestConfig(buildRequestConfig()).setDefaultSocketConfig(buildSocketConfig());

		clientBuilder.setMaxConnTotal(maxConnections);

		if (useSystemProperties)
			clientBuilder = clientBuilder.useSystemProperties();
		return clientBuilder.build();
	}

	@Override
	public void destroy() {
		if (proxyClient instanceof Closeable) {
			try {
				((Closeable) proxyClient).close();
			} catch (IOException e) {
				log("Destroying servlet, shutting down HttpClient: " + e, e);
			}
		} else {
			if (proxyClient != null)
				proxyClient = null;
		}
		super.destroy();
	}

	@Override
	protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws ServletException, IOException {

		if (servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_URI) == null) {
			servletRequest.setAttribute(ProxyConstants.ATTR_TARGET_URI, targetUri);
		}
		if (servletRequest.getAttribute(ProxyConstants.ATTR_TARGET_HOST) == null) {
			servletRequest.setAttribute(ProxyConstants.ATTR_TARGET_HOST, targetHost);
		}

		String method = servletRequest.getMethod();
		String proxyRequestUri = rewriteUrlFromRequest(servletRequest);
		HttpRequest proxyRequest;

		if (servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH) != null || servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
			proxyRequest = newProxyRequestWithEntity(method, proxyRequestUri, servletRequest);
		} else {
			proxyRequest = new BasicHttpRequest(method, proxyRequestUri);
		}

		copyRequestHeaders(servletRequest, proxyRequest);

		setXForwardedForHeader(servletRequest, proxyRequest);

		HttpResponse proxyResponse = null;
		try {

			proxyResponse = doExecute(servletRequest, servletResponse, proxyRequest);

			int statusCode = proxyResponse.getStatusLine().getStatusCode();

			// servletResponse.setStatus(statusCode,
			// proxyResponse.getStatusLine().getReasonPhrase());
			servletResponse.setStatus(statusCode);
			copyResponseHeaders(proxyResponse, servletRequest, servletResponse);

			if (statusCode == HttpServletResponse.SC_NOT_MODIFIED) {

				servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
			} else {

				copyResponseEntity(proxyResponse, servletResponse, proxyRequest, servletRequest);
			}

		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (proxyResponse != null) {
				EntityUtils.consumeQuietly(proxyResponse.getEntity());
			}
		}
	}

	private HttpResponse doExecute(HttpServletRequest servletRequest, HttpServletResponse servletResponse, HttpRequest proxyRequest) throws IOException {
		log("proxy " + servletRequest.getMethod() + " uri: " + servletRequest.getRequestURI() + " -- " + proxyRequest.getRequestLine().getUri());
		return proxyClient.execute(getTargetHost(servletRequest), proxyRequest);
	}

	private HttpRequest newProxyRequestWithEntity(String method, String proxyRequestUri, HttpServletRequest servletRequest) throws IOException {
		HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, proxyRequestUri);
		// Add the input entity (streamed)
		// note: we don't bother ensuring we close the servletInputStream since the
		// container handles it
		eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), getContentLength(servletRequest)));
		return eProxyRequest;
	}

	// Get the header value as a long in order to more correctly proxy very large
	// requests
	private long getContentLength(HttpServletRequest request) {
		String contentLengthHeader = request.getHeader("Content-Length");
		if (contentLengthHeader != null) {
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

	/**
	 * Copy request headers from the servlet client to the proxy request. This is
	 * easily overridden to add your own.
	 */
	private void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest) {
		// Get an Enumeration of all of the header names sent by the client

		Enumeration<String> enumerationOfHeaderNames = servletRequest.getHeaderNames();
		while (enumerationOfHeaderNames.hasMoreElements()) {
			String headerName = enumerationOfHeaderNames.nextElement();
			copyRequestHeader(servletRequest, proxyRequest, headerName);
		}
	}

	/**
	 * Copy a request header from the servlet client to the proxy request. This is
	 * easily overridden to filter out certain headers if desired.
	 */
	private void copyRequestHeader(HttpServletRequest servletRequest, HttpRequest proxyRequest, String headerName) {
		if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
			return;
		if (hopByHopHeaders.containsHeader(headerName))
			return;
		Enumeration<String> headers = servletRequest.getHeaders(headerName);
		while (headers.hasMoreElements()) {// sometimes more than one value
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
			if (existingForHeader != null) {
				forHeader = existingForHeader + ", " + forHeader;
			}
			proxyRequest.setHeader(forHeaderName, forHeader);

			String protoHeaderName = "X-Forwarded-Proto";
			String protoHeader = servletRequest.getScheme();
			proxyRequest.setHeader(protoHeaderName, protoHeader);
		}
	}

	/** Copy proxied response headers back to the servlet client. */
	private void copyResponseHeaders(HttpResponse proxyResponse, HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		for (Header header : proxyResponse.getAllHeaders()) {
			copyResponseHeader(servletRequest, servletResponse, header);
		}
	}

	/**
	 * Copy a proxied response header back to the servlet client. This is easily
	 * overwritten to filter out certain headers if desired.
	 */
	private void copyResponseHeader(HttpServletRequest servletRequest, HttpServletResponse servletResponse, Header header) {
		String headerName = header.getName();
		if (hopByHopHeaders.containsHeader(headerName))
			return;
		String headerValue = header.getValue();
		if (headerName.equalsIgnoreCase(HttpHeaders.LOCATION)) {
			servletResponse.addHeader(headerName, rewriteUrlFromResponse(servletRequest, headerValue));
		} else {
			servletResponse.addHeader(headerName, headerValue);
		}
	}

	/**
	 * Copy response body data (the entity) from the proxy to the servlet client.
	 */
	private void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse, HttpRequest proxyRequest, HttpServletRequest servletRequest) throws IOException {
		HttpEntity entity = proxyResponse.getEntity();
		if (entity != null) {
			OutputStream servletOutputStream = servletResponse.getOutputStream();
			entity.writeTo(servletOutputStream);
		}
	}

	/**
	 * Reads the request URI from {@code servletRequest} and rewrites it,
	 * considering targetUri. It's used to make the new request.
	 */
	private String rewriteUrlFromRequest(HttpServletRequest servletRequest) {
		StringBuilder uri = new StringBuilder(500);
		uri.append(getTargetUri(servletRequest));
		// Handle the path given to the servlet
		String pathInfo = rewritePathInfoFromRequest(servletRequest);
		if (pathInfo != null) {// ex: /my/path.html
			// getPathInfo() returns decoded string, so we need encodeUriQuery to encode "%"
			// characters
			uri.append(encodeUriQuery(pathInfo, true));
		}
		// Handle the query string & fragment
		String queryString = servletRequest.getQueryString();// ex:(following '?'): name=value&foo=bar#fragment
		String fragment = null;
		// split off fragment from queryString, updating queryString if found
		if (queryString != null) {
			int fragIdx = queryString.indexOf('#');
			if (fragIdx >= 0) {
				fragment = queryString.substring(fragIdx + 1);
				queryString = queryString.substring(0, fragIdx);
			}
		}

		queryString = rewriteQueryStringFromRequest(servletRequest, queryString);
		if (queryString != null && queryString.length() > 0) {
			uri.append('?');
			// queryString is not decoded, so we need encodeUriQuery not to encode "%"
			// characters, to avoid double-encoding
			uri.append(encodeUriQuery(queryString, false));
		}

		if (doSendUrlFragment && fragment != null) {
			uri.append('#');
			// fragment is not decoded, so we need encodeUriQuery not to encode "%"
			// characters, to avoid double-encoding
			uri.append(encodeUriQuery(fragment, false));
		}
		return uri.toString();
	}

	private String rewriteQueryStringFromRequest(HttpServletRequest servletRequest, String queryString) {
		return queryString;
	}

	/**
	 * Allow overrides of
	 * {@link javax.servlet.http.HttpServletRequest#getPathInfo()}. Useful when
	 * url-pattern of servlet-mapping (web.xml) requires manipulation.
	 */
	private String rewritePathInfoFromRequest(HttpServletRequest servletRequest) {
		return servletRequest.getPathInfo();
	}

	
	private String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl) {
		// TODO document example paths
		final String targetUri = getTargetUri(servletRequest);
		if (theUrl.startsWith(targetUri)) {
			StringBuffer curUrl = servletRequest.getRequestURL();// no query
			int pos;
			// Skip the protocol part
			if ((pos = curUrl.indexOf("://")) >= 0) {
				// Skip the authority part
				// + 3 to skip the separator between protocol and authority
				if ((pos = curUrl.indexOf("/", pos + 3)) >= 0) {
					// Trim everything after the authority part.
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

	/** The target URI as configured. Not null. */
	public String getTargetUri() {
		return targetUri;
	}

	/**
	 * Encodes characters in the query or fragment part of the URI.
	 *
	 * <p>
	 * Unfortunately, an incoming URI sometimes has characters disallowed by the
	 * spec. HttpClient insists that the outgoing proxied request has a valid URI
	 * because it uses Java's {@link URI}. To be more forgiving, we must escape the
	 * problematic characters. See the URI class for the spec.
	 *
	 * @param in            example: name=value&amp;foo=bar#fragment
	 * @param encodePercent determine whether percent characters need to be encoded
	 */
	private CharSequence encodeUriQuery(CharSequence in, boolean encodePercent) {
		// Note that I can't simply use URI.java to encode because it will escape
		// pre-existing escaped things.
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
				// leading %, 0 padded, width 2, capital hex
				formatter.format("%%%02X", (int) c);// TODO
			}
		}
		return outBuf != null ? outBuf : in;
	}

	private static final BitSet asciiQueryChars;
	static {
		char[] c_unreserved = "_-!.~'()*".toCharArray();// plus alphanum
		char[] c_punct = ",;:$&+=".toCharArray();
		char[] c_reserved = "?/[]@".toCharArray();// plus punct

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

		asciiQueryChars.set((int) '%');// leave existing percent escapes in place
	}

}
