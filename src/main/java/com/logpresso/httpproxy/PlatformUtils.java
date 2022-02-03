package com.logpresso.httpproxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class PlatformUtils {
	public final static boolean isWindows;
	public final static boolean isUnixLike;
	public final static boolean isLinux;
	public final static boolean isAIX;
	public final static boolean isHPUX;
	public final static boolean isSolaris;
	public final static boolean isMacOS;
	static {
		String osname = System.getProperty("os.name").toLowerCase();
		isWindows = osname.startsWith("win");
		isLinux = osname.startsWith("linux");
		isAIX = osname.contains("aix");
		isHPUX = osname.contains("hpux") || osname.contains("hp-ux");
		isSolaris = osname.contains("solaris") || osname.contains("sunos");
		isMacOS = osname.contains("mac");
		isUnixLike = isLinux || isAIX || isHPUX || isSolaris || isMacOS;
		if (!isWindows && !isUnixLike)
			throw new UnsupportedOperationException();
	}

	public static String getHostname(boolean debug) {
		// Try to fetch hostname without DNS resolving for closed network
		boolean isWindows = File.separatorChar == '\\';
		if (isWindows) {
			return System.getenv("COMPUTERNAME");
		} else {
			Process p = null;
			try {
				p = Runtime.getRuntime().exec("uname -n");
				BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

				String line = br.readLine();
				return (line == null) ? null : line.trim();
			} catch (IOException e) {
				if (debug)
					e.printStackTrace();

				return null;
			} finally {
				if (p != null)
					p.destroy();
			}
		}
	}

	public static String getHomeDir() {
		if (isUnixLike)
			return PlatformUtils.getenv("HOME");
		else
			return PlatformUtils.getenv("USERPROFILE");
	}

	public static String getenv(String var) {
		String val = System.getenv(var);
		if (val == null || val.trim().isEmpty())
			return null;
		else
			return val;
	}

	public static String resolvePath(String command) {
		if (new File(command).isAbsolute())
			return command;

		String pathEnv = PlatformUtils.getenv("PATH");
		if (pathEnv == null || pathEnv.trim().isEmpty())
			pathEnv = "";

		String[] paths = pathEnv.split(Pattern.quote(System.getProperty("path.separator")));
		for (String path : paths) {
			File candidate = new File(path, command);
			if (candidate.exists() && candidate.canExecute())
				return candidate.toString();
		}

		return command;
	}

	public static List<String> execute(String... commands) throws IOException {
		List<String> output = new ArrayList<String>();
		Process p = null;
		BufferedReader br = null;
		try {
			commands[0] = resolvePath(commands[0]);
			ProcessBuilder pb = new ProcessBuilder(commands);
			pb.redirectErrorStream(true);
			p = pb.start();
			br = new BufferedReader(new InputStreamReader(p.getInputStream()));
			while (true) {
				String line = br.readLine();
				if (line == null)
					break;

				output.add(line);
			}

			return output;
		} finally {
			if (br != null) {
				try {
					br.close();
				} catch (Throwable t) {
				}
			}

			if (p != null) {
				try {
					p.waitFor();
				} catch (Throwable t) {
				}
			}
		}
	}

	public static boolean isRoot() throws IOException {
		if (!isLinux)
			return false;

		List<String> output = execute("/usr/bin/id", "-u");
		if (output.isEmpty())
			return false;

		return output.get(0).equals("0");
	}
}