package com.logpresso.httpproxy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

public class Configuration {
	private int port;
	private boolean debug;
	private Set<String> allowlist = new HashSet<String>();

	public static Configuration load() throws IOException {
		Configuration c = new Configuration();
		Pattern regex = Pattern.compile("\\s+");
		File f = new File(getJarDir(), "logpresso-http-proxy.conf");
		BufferedReader br = null;

		boolean allowlistSection = false;
		try {
			br = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
			while (true) {
				String line = br.readLine();
				if (line == null)
					break;

				line = line.trim();

				if (line.isEmpty() || line.startsWith("#"))
					continue;

				String[] tokens = regex.split(line);
				String descriptor = tokens[0];

				if (descriptor.equals("port")) {
					try {
						c.port = Integer.parseInt(getValue(tokens, "port number is missing"));
						if (c.port < 0 || c.port > 65535)
							throw new IllegalStateException("Invalid port number range: " + tokens[1]);

					} catch (NumberFormatException e) {
						throw new IllegalStateException("Invalid port number: " + tokens[1]);
					}
				} else if (descriptor.equals("loglevel")) {
					c.debug = "debug".equals(getValue(tokens, "loglevel value is missing"));
				} else if (descriptor.equals("[allowlist]")) {
					allowlistSection = true;
				} else {
					if (allowlistSection) {
						c.allowlist.add(tokens[0]);
					}
				}
			}

			return c;
		} finally {
			if (br != null)
				br.close();
		}
	}

	private static String getValue(String[] tokens, String error) {
		if (tokens.length < 2)
			throw new IllegalStateException("port number is missing");

		return tokens[1];

	}

	public static void install() {
		installConfigFile();
		installSystemdFile();
	}

	private static void installConfigFile() {
		File dir = getJarDir();
		File configFile = new File(dir, "logpresso-http-proxy.conf");
		configFile.getParentFile().mkdirs();
		if (configFile.exists())
			throw new IllegalStateException("Cannot write file to " + configFile.getAbsolutePath());

		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), "utf-8"));
			bw.write("# Logpresso HTTP proxy config file\n");
			bw.write("port 8443\n");
			bw.write("[allowlist]\n");
			bw.write("# host:port\n");
			bw.write("# logpresso.watch:443\n");
		} catch (IOException e) {
			throw new IllegalStateException("cannot write config file to " + configFile.getAbsolutePath(), e);
		} finally {
			if (bw != null) {
				try {
					bw.close();
				} catch (IOException e) {
				}
			}
		}

		System.out.println("Wrote " + configFile.length() + " bytes to " + configFile.getAbsolutePath());
	}

	private static void installSystemdFile() {
		File dir = getJarDir();
		File serviceFile = new File("/lib/systemd/system/logpresso-http-proxy.service");
		BufferedWriter bw = null;
		try {
			bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(serviceFile), "utf-8"));
			bw.write("[Unit]\n");
			bw.write("Description=Logpresso HTTP proxy\n");
			bw.write("After=multi-user.target network.target\n");
			bw.write("ConditionPathExists=" + dir.getAbsolutePath() + "/logpresso-http-proxy.conf\n\n");
			bw.write("[Service]\n");
			bw.write("Type=simple\n");
			bw.write("LimitNOFILE=65536\n");
			bw.write("ExecStart=" + dir.getAbsolutePath() + "/logpresso-http-proxy start\n");
			bw.write("Restart=on-failure\n");
			bw.write("[Install]\n");
			bw.write("WantedBy=multi-user.target\n");
		} catch (IOException e) {
			throw new IllegalStateException("cannot write systemd file to " + serviceFile.getAbsolutePath(), e);
		} finally {
			if (bw != null) {
				try {
					bw.close();
				} catch (IOException e) {
				}
			}
		}

		System.out.println("Wrote " + serviceFile.length() + " bytes to " + serviceFile.getAbsolutePath());

		reloadSystemd();
	}

	public static void uninstall() {
		File serviceFile = new File("/lib/systemd/system/logpresso-http-proxy.service");
		if (!serviceFile.exists()) {
			System.out.println("Error: service file not found");
			return;
		}

		if (serviceFile.delete()) {
			System.out.println("uninstalled systemd service");
		} else {
			System.out.println("Cannot delete service file " + serviceFile.getAbsolutePath());
		}
		
		reloadSystemd();
	}

	private static void reloadSystemd() {
		try {
			PlatformUtils.execute("systemctl", "daemon-reload");
		} catch (IOException e) {
		}
	}

	public static File getJarDir() {
		try {
			File jarPath = new File(URLDecoder
					.decode(Configuration.class.getProtectionDomain().getCodeSource().getLocation().getPath(), "utf-8"));
			return jarPath.getParentFile();
		} catch (UnsupportedEncodingException e) {
			// unreachable
			throw new IllegalStateException(e);
		}
	}

	public int getPort() {
		return port;
	}

	public boolean isDebug() {
		return debug;
	}

	public Set<String> getAllowlist() {
		return allowlist;
	}

}
