package com.logpresso.httpproxy;

import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConnectRequest {
	private String httpMethod;
	private String requestTarget;
	private InetSocketAddress endpoint;
	private String version;
	private Map<String, String> headers;

	public static ConnectRequest parse(String s) {
		s = s.trim();
		if (s.isEmpty())
			throw new IllegalStateException("Empty HTTP header");

		String[] lines = s.split("\n");
		String requestLine = lines[0].trim();
		String[] requestTokens = requestLine.split(" ");
		String httpMethod = requestTokens[0];

		if (requestTokens.length < 3)
			throw new IllegalStateException("Malformed HTTP request: " + requestLine);

		if (!requestTokens[2].toUpperCase().startsWith("HTTP/"))
			throw new IllegalStateException("Malformed HTTP request: " + requestLine);

		String target = requestTokens[1];
		String host = null;
		int port = 80;

		if (httpMethod.toUpperCase().equals("CONNECT")) {
			int p = target.indexOf(':');
			if (p < 0)
				throw new IllegalStateException("Malformed CONNECT request target: " + target);

			host = target.substring(0, p);
			port = Integer.parseInt(target.substring(p + 1));
		} else {
			try {
				URL url = new URL(target);
				host = url.getHost();
				if (url.getPort() != -1)
					port = url.getPort();
			} catch (MalformedURLException e) {
				throw new IllegalStateException("Malformed HTTP request: " + requestLine, e);
			}
		}

		ConnectRequest req = new ConnectRequest();
		req.httpMethod = httpMethod;
		req.requestTarget = target;
		req.endpoint = new InetSocketAddress(host, port);
		req.version = requestTokens[2];
		req.headers = new LinkedHashMap<String, String>();

		for (int i = 1; i < lines.length; i++) {
			int p = lines[i].indexOf(':');
			String name = lines[i].substring(0, p).trim();
			String value = lines[i].substring(p + 1).trim();
			req.headers.put(name, value);
		}

		return req;
	}

	public String getHttpMethod() {
		return httpMethod;
	}

	public String getRequestTarget() {
		return requestTarget;
	}

	public InetSocketAddress getEndpoint() {
		return endpoint;
	}

	public String getHttpVersion() {
		return version;
	}

	public List<String> getHeaderNames() {
		return new ArrayList<String>(headers.keySet());
	}

	public String getHeader(String name) {
		return headers.get(name);
	}

	@Override
	public String toString() {
		return "CONNECT " + endpoint;
	}

}
