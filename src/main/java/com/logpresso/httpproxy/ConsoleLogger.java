package com.logpresso.httpproxy;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ConsoleLogger implements Logger {

	private SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
	private boolean debug;

	@Override
	public void setDebug(boolean enabled) {
		this.debug = enabled;
	}

	@Override
	public boolean isDebugEnabled() {
		return debug;
	}

	@Override
	public void debug(String msg) {
		if (!debug)
			return;

		String timestamp = df.format(new Date());
		System.out.println(String.format("[%s] [DEBUG] %s", timestamp, msg));
	}

	@Override
	public void warn(String msg) {
		String timestamp = df.format(new Date());
		System.out.println(String.format("[%s] [ WARN] %s", timestamp, msg));
	}

	@Override
	public void info(String msg) {
		String timestamp = df.format(new Date());
		System.out.println(String.format("[%s] [ INFO] %s", timestamp, msg));
	}

	@Override
	public void error(String msg) {
		String timestamp = df.format(new Date());
		System.out.println(String.format("[%s] [ERROR] %s", timestamp, msg));
	}

}
