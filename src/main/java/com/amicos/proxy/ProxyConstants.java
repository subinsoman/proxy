package com.amicos.proxy;


public class ProxyConstants {
	
	public static final String SERVICE_NAME = "SERVICE_NAME";
	
	public static final String P_FORWARDEDFOR = "forwardip";

	public static final String P_PRESERVEHOST = "preserveHost";

	public static final String P_PRESERVECOOKIES = "preserveCookies";

	public static final String P_HANDLEREDIRECTS = "http.protocol.handle-redirects";

	public static final String P_CONNECTTIMEOUT = "http.socket.timeout";

	public static final String P_READTIMEOUT = "http.read.timeout";

	public static final String P_CONNECTIONREQUESTTIMEOUT = "http.connectionrequest.timeout";

	public static final String P_MAXCONNECTIONS = "http.maxConnections";

	public static final String P_USESYSTEMPROPERTIES = "useSystemProperties";

	public static final String P_TARGET_URI = "targetUri";
	
	public static final String ATTR_TARGET_URI = ProxyServlet.class.getSimpleName() + ".targetUri";
	
	public static final String ATTR_TARGET_HOST = ProxyServlet.class.getSimpleName() + ".targetHost";

}
