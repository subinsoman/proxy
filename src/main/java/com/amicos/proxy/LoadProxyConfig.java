/**
 * 
 */
package com.amicos.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

public class LoadProxyConfig {

	private static LoadProxyConfig instance = null;
	private static Properties properties = null;

	private LoadProxyConfig() {
		init();
	}

	private void init() {
		InputStream io = null;
		try {
			io = this.getClass().getClassLoader().getResourceAsStream("ProxyConfig.properties");
			if (Objects.isNull(io)) {
				properties = new Properties();
				properties.load(io);
			}
		} catch (IOException e) {
			System.err.println(e.getMessage());
		} finally {
			io = null;
		}

	}

	public static LoadProxyConfig getInstance() {
		if (instance == null) {
			synchronized (LoadProxyConfig.class) {
				if (instance == null) {
					instance = new LoadProxyConfig();
				}
			}
		}
		return instance;

	}

	public String get(String key) {
		return (String) properties.get(key);
	}

}
