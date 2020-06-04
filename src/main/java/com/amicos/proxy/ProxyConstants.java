package com.amicos.proxy;


public class ProxyConstants {
	/** A boolean parameter name to enable forwarding of the client IP */
	public static final String P_FORWARDEDFOR = "forwardip";

	/** A boolean parameter name to keep HOST parameter as-is */
	public static final String P_PRESERVEHOST = "preserveHost";

	/** A boolean parameter name to keep COOKIES as-is */
	public static final String P_PRESERVECOOKIES = "preserveCookies";

	/** A boolean parameter name to have auto-handle redirects */
	public static final String P_HANDLEREDIRECTS = "http.protocol.handle-redirects";

	/** A integer parameter name to set the socket connection timeout (millis) */
	public static final String P_CONNECTTIMEOUT = "http.socket.timeout";

	/** A integer parameter name to set the socket read timeout (millis) */
	public static final String P_READTIMEOUT = "http.read.timeout";

	/** A integer parameter name to set the connection request timeout (millis) */
	public static final String P_CONNECTIONREQUESTTIMEOUT = "http.connectionrequest.timeout";

	/** A integer parameter name to set max connection number */
	public static final String P_MAXCONNECTIONS = "http.maxConnections";

	/**
	 * A boolean parameter whether to use JVM-defined system properties to configure
	 * various networking aspects.
	 */
	public static final String P_USESYSTEMPROPERTIES = "useSystemProperties";

	/** The parameter name for the target (destination) URI to proxy to. */
	public static final String P_TARGET_URI = "targetUri";
	public static final String ATTR_TARGET_URI = ProxyServlet.class.getSimpleName() + ".targetUri";
	public static final String ATTR_TARGET_HOST = ProxyServlet.class.getSimpleName() + ".targetHost";

}
